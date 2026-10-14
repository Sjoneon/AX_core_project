package com.project.backend.conversation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.project.backend.conversation.dto.ConversationDetail;
import com.project.backend.conversation.dto.ConversationSummary;
import com.project.backend.conversation.dto.ChatContext;
import com.project.backend.conversation.dto.ChatContext.HistoryMessage;
import com.project.backend.conversation.dto.MessageResponse;
import com.project.backend.user.AppUserRepository;
import com.project.backend.rag.RagSource;

@Service
public class ConversationService {

    private static final int MAX_HISTORY_CHARACTERS = 24000;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AppUserRepository userRepository;

    public ConversationService(ConversationRepository conversationRepository,
            ChatMessageRepository messageRepository, AppUserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
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

    private ProductCategory resolveCategory(Long id, String email, ProductCategory requestedCategory) {
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
    public Long saveExchange(Long id, String email, String message, String reply, ProductCategory category, List<RagSource> sources) {
        Conversation conversation;
        if (id == null) {
            var user = userRepository.findByEmail(email).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."));
            conversation = conversationRepository.save(new Conversation(user, message, category));
        } else {
            conversation = findOwned(id, email);
            conversation.touch();
        }
        var answer = new ChatMessage(conversation, "assistant", reply);
        answer.setSources(sources);
        messageRepository.saveAll(List.of(new ChatMessage(conversation, "user", message), answer));
        return conversation.getId();
    }

    @Transactional
    public void delete(Long id, String email) {
        Conversation conversation = findOwned(id, email);
        messageRepository.deleteAllByConversationId(id);
        conversationRepository.delete(conversation);
    }

    private Conversation findOwned(Long id, String email) {
        return conversationRepository.findByIdAndUserEmail(id, email).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "대화를 찾을 수 없습니다."));
    }
}
