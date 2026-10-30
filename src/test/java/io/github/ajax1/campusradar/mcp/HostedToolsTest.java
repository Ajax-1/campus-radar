package io.github.ajax1.campusradar.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "radar.hosted.enabled=true")
class HostedToolsTest {

    @Autowired
    private ToolCallbackProvider tools;

    @Test
    void publicInstanceOnlyExposesJobTools() {
        assertThat(Arrays.stream(tools.getToolCallbacks()).map(c -> c.getToolDefinition().name()))
                .containsExactlyInAnyOrder("search_jobs", "get_job", "list_sources");
    }
}
