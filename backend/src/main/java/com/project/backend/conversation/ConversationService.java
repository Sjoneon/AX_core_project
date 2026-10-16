package com.project.backend.conversation;

import static java.util.Objects.requireNonNull;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.ArrayList;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.project.backend.conversation.dto.ConversationDetail;
import com.project.backend.conversation.dto.ConversationSummary;
import com.project.backend.conversation.dto.ChatContext;
import com.project.backend.conversation.dto.ChatContext.HistoryMessage;
import com.project.backend.conversation.dto.MessageResponse;
import com.project.backend.mcp.audit.ToolCallLog;
import com.project.backend.mcp.audit.ToolCallLogRepository;
import com.project.backend.mcp.audit.ToolCallRecord;
import com.project.backend.user.AppUserRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.project.backend.rag.RagSource;
import com.project.backend.warranty.WarrantyModels;

@Service
public class ConversationService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_HISTORY_CHARACTERS = 24000;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AppUserRepository userRepository;
    private final ToolCallLogRepository toolCallRepository;

    public ConversationService(ConversationRepository conversationRepository,
            ChatMessageRepository messageRepository, AppUserRepository userRepository, ToolCallLogRepository toolCallRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.toolCallRepository = toolCallRepository;
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> list(String email) {
        return conversationRepository.findByUserEmailOrderByUpdatedAtDescIdDesc(email).stream()
                .map(ConversationSummary::from).toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetail detail(Long id, String email) {
        Conversation conversation = findOwned(id, email);
        var messages = messageRepository.findByConversationIdOrderByIdAsc(id).stream()
                .map(MessageResponse::from).toList();
        return new ConversationDetail(conversation.getId(), conversation.getTitle(), conversation.getCategory(), messages);
    }

    @Transactional(readOnly = true)
    public ChatContext loadChatContext(Long id, String email, ProductCategory requestedCategory) {
        ProductCategory category = resolveCategory(id, email, requestedCategory);
        if (id == null) {
            return new ChatContext(category, List.of());
        }
        var messages = messageRepository.findTop20ByConversationIdOrderByIdDesc(id).reversed().stream()
                .map(message -> new HistoryMessage(message.getRole(), message.getContent())).toList();
        int characters = messages.stream().mapToInt(message -> message.content().length()).sum();
        int start = 0;
        // Successful exchanges are stored atomically as a user/assistant pair.
        while (characters > MAX_HISTORY_CHARACTERS) {
            characters -= messages.get(start).content().length() + messages.get(start + 1).content().length();
            start += 2;
        }
        return new ChatContext(category, messages.subList(start, messages.size()));
    }

    @Transactional(readOnly = true)
    public ProductCategory resolveCategory(Long id, String email, ProductCategory requestedCategory) {
        if (id != null) {
            ProductCategory savedCategory = findOwned(id, email).getCategory();
            rejectUnsupported(savedCategory);
            rejectUnsupported(requestedCategory);
            if (requestedCategory != null && requestedCategory != savedCategory) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "기존 상담의 카테고리는 변경할 수 없습니다.");
            }
            return savedCategory;
        } else if (!userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요.");
        }
        rejectUnsupported(requestedCategory);
        return requestedCategory;
    }

    private void rejectUnsupported(ProductCategory category) {
        if (category != null && !category.isSupported()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "에어컨 상담은 지원 종료되었습니다. 기존 기록만 조회할 수 있습니다.");
        }
    }

    // Keep the external AI call outside this transaction; save both messages together.
    @Transactional
    public Long saveExchange(Long id, String email, String message, String reply, ProductCategory category, List<RagSource> sources, WarrantyModels.Result warranty) {
        return saveExchange(id,email,message,reply,category,sources,warranty,java.util.Map.of());
    }

    @Transactional
    public Long saveExchange(Long id, String email, String message, String reply, ProductCategory category, List<RagSource> sources, WarrantyModels.Result warranty,
            java.util.Map<String,com.project.backend.mcp.tool.ToolEnvelope> tools) {
        return saveExchange(id, email, message, reply, category, sources, warranty, tools, List.of());
    }

    // Tool call rows are stored with the assistant message in the same transaction.
    @Transactional
    public Long saveExchange(Long id, String email, String message, String reply, ProductCategory category, List<RagSource> sources, WarrantyModels.Result warranty,
            java.util.Map<String,com.project.backend.mcp.tool.ToolEnvelope> tools, List<ToolCallRecord> calls) {
        Conversation conversation;
        if (id == null) {
            var user = userRepository.findByEmail(email).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."));
            conversation = conversationRepository.save(new Conversation(user, message, category));
        } else {
            conversation = findOwned(id, email);
            for(var result:tools.values()){
                if(result.meta()!=null&&result.meta().get("context_version") instanceof Number version&&version.longValue()!=conversation.getContextRevision())
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"상담 상태가 변경됐습니다. 다시 확인해 주세요.");
            }
            conversation.touch();
        }
        var answer = new ChatMessage(conversation, "assistant", reply);
        answer.setSources(sources);
        answer.setWarranty(warranty);
        answer.setTools(tools);
        messageRepository.saveAll(requireNonNull(List.<@NonNull ChatMessage>of(new ChatMessage(conversation, "user", message), requireNonNull(answer))));
        for (int order = 0; order < calls.size(); order++) {
            var call = calls.get(order);
            toolCallRepository.save(new ToolCallLog(conversation.getId(), answer.getId(), order + 1, call,
                    JSON.writeValueAsString(call.arguments()), JSON.writeValueAsString(call.summary())));
        }
        return conversation.getId();
    }

    /** Tool call records of the caller's own conversation only. */
    @Transactional(readOnly = true)
    public List<ToolCallView> toolCalls(Long id, String email) {
        findOwned(id, email);
        return toolCallRepository.findByConversationIdOrderByIdAsc(id).stream().map(ToolCallView::from).toList();
    }

    @Transactional
    public void delete(Long id, String email) {
        Conversation conversation = findOwned(id, email);
        // V005 also cascades in MySQL; deleting here keeps H2 tests and older schemas consistent.
        toolCallRepository.deleteByConversation(id);
        messageRepository.deleteAllByConversationId(id);
        conversationRepository.delete(conversation);
    }

    public record ToolCallView(int callOrder, Long messageId, String toolName, java.util.Map<String, Object> arguments,
            List<String> injectedFields, String selectionSource, String status, String errorCode,
            java.util.Map<String, Object> resultSummary, long elapsedMs, String traceId, java.time.Instant createdAt) {
        static ToolCallView from(ToolCallLog log) {
            return new ToolCallView(log.getCallOrder(), log.getMessageId(), log.getToolName(), json(log.getArguments()),
                    log.getInjectedFields() == null || log.getInjectedFields().isBlank() ? List.of() : List.of(log.getInjectedFields().split(",")),
                    log.getSelectionSource(), log.getStatus(), log.getErrorCode(), json(log.getResultSummary()),
                    log.getElapsedMs(), log.getTraceId(), log.getCreatedAt());
        }
        private static java.util.Map<String, Object> json(String value) {
            return value == null ? java.util.Map.of() : JSON.readValue(value, new TypeReference<java.util.Map<String, Object>>() { });
        }
    }

    private Conversation findOwned(Long id, String email) {
        return conversationRepository.findByIdAndUserEmail(id, email).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다."));
    }

    /** Bounded text and the last displayed step, without raw documents, credentials or customer profile. */
    public record DialogueMessage(String role,String content,String displayedStep,Long contextVersion,String displayedCondition,String planScope) {
        public DialogueMessage(String role,String content,String displayedStep,Long contextVersion){this(role,content,displayedStep,contextVersion,null,null);}
    }
    /** True when an earlier answer here already explained this symptom/error without an approved self-care plan. */
    @Transactional(readOnly=true)
    public boolean explainedWithoutApproval(Long id,String email,String symptomCode,String errorCode){
        if(symptomCode==null&&errorCode==null)return false;
        findOwned(id,email);
        return messageRepository.findTop12ByConversationIdOrderByIdDesc(id).stream().map(message->message.getTools().get("get_diagnostic_plan"))
                .anyMatch(plan->plan!=null&&plan.data()!=null&&"NOT_VERIFIED".equals(plan.data().get("skip_reason"))
                        &&java.util.Objects.equals(plan.data().get("symptom_code"),symptomCode)&&java.util.Objects.equals(plan.data().get("error_code"),errorCode));
    }
    @Transactional(readOnly=true)
    public List<DialogueMessage> dialogueHistory(Long id,String email){
        findOwned(id,email);
        var recent=new ArrayList<>(messageRepository.findTop12ByConversationIdOrderByIdDesc(id));
        java.util.Collections.reverse(recent);
        return recent.stream().map(message->{
            String step=null,condition=null,scope=null;Long version=null;var plan=message.getTools().get("get_diagnostic_plan");
            if(plan!=null&&"OK".equals(plan.status())&&plan.data()!=null&&plan.data().get("steps") instanceof List<?> steps&&!steps.isEmpty())
                step=java.util.Objects.toString(((java.util.Map<?,?>)requireNonNull(steps.getFirst())).get("step_code"),null);
            if(plan!=null&&plan.meta()!=null&&plan.meta().get("context_version") instanceof Number value)version=value.longValue();
            if(plan!=null&&"NEEDS_INPUT".equals(plan.status())&&plan.data()!=null&&plan.data().get("question") instanceof java.util.Map<?,?> q){
                condition=java.util.Objects.toString(q.get("id"),null);scope=java.util.Objects.toString(plan.data().get("plan_scope"),null);
            }
            String content=message.getContent();
            return new DialogueMessage(message.getRole(),content.substring(0,Math.min(1200,content.length())),step,version,condition,scope);
        }).toList();
    }
}
