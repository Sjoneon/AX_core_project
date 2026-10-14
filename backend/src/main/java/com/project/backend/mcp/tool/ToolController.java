package com.project.backend.mcp.tool;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Internal tool API for the MCP container only. Nginx also blocks /api/tools/ from the internet. */
@RestController
public class ToolController {
    private final ToolBackend tools;
    public ToolController(ToolBackend tools){this.tools=tools;}
    @PostMapping("/api/tools/{name}") public ToolEnvelope call(@PathVariable String name,@RequestBody Map<String,Object> args,
            @RequestHeader(name="X-HomeCare-Context-Version",required=false) Long version,
            @RequestHeader(name="X-Forwarded-For",required=false) String forwarded,Principal principal){
        // Nginx always sets X-Forwarded-For; the MCP container calls the backend directly and never does.
        if(forwarded!=null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"도구를 찾을 수 없습니다.");
        return tools.execute(name,principal.getName(),args,version);
    }
}
