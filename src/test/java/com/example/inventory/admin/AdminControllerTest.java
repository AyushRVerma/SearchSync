package com.example.inventory.admin;

import com.example.inventory.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private ProductReconciliationService reconciliationService;

    @Mock
    private DltAdminService dltAdminService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(reconciliationService, dltAdminService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void reindexWithPayloadReturns200() throws Exception {
        ReindexResponse expectedResponse = new ReindexResponse("products-v2", 100L, 100L, 0L, 500L, true, "SUCCESS");
        when(reconciliationService.reindex(any())).thenReturn(expectedResponse);

        mockMvc.perform(post("/api/v1/admin/reindex")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "targetIndex": "products-v2",
                                    "batchSize": 200,
                                    "switchAlias": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.targetIndex").value("products-v2"))
                .andExpect(jsonPath("$.totalProductsInDatabase").value(100))
                .andExpect(jsonPath("$.indexedCount").value(100))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.aliasSwitched").value(true));
    }

    @Test
    void reindexWithoutBodyUsesDefaults() throws Exception {
        ReindexResponse expectedResponse = new ReindexResponse("products-v1", 50L, 50L, 0L, 200L, false, "SUCCESS");
        when(reconciliationService.reindex(any())).thenReturn(expectedResponse);

        mockMvc.perform(post("/api/v1/admin/reindex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.targetIndex").value("products-v1"));
    }

    @Test
    void switchAliasSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/admin/switch-alias")
                        .param("targetIndex", "products-v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.targetIndex").value("products-v2"));

        verify(reconciliationService).switchAliasTo("products-v2");
    }

    @Test
    void switchAliasWithEmptyTargetIndexReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/switch-alias")
                        .param("targetIndex", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("targetIndex must not be blank"));
    }

    @Test
    void getDltMessagesReturnsList() throws Exception {
        DltMessageResponse msg = new DltMessageResponse(0, 5L, "key", "val", 123456789L,
                Map.of("header1", "val1"), "java.lang.IllegalArgumentException", "Invalid field",
                "inventory.public.products", 0, 5L);
        when(dltAdminService.getMessages(50)).thenReturn(List.of(msg));

        mockMvc.perform(get("/api/v1/admin/dlt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].offset").value(5))
                .andExpect(jsonPath("$[0].exceptionClass").value("java.lang.IllegalArgumentException"))
                .andExpect(jsonPath("$[0].exceptionMessage").value("Invalid field"));
    }

    @Test
    void replayDltMessagesReturnsStatus() throws Exception {
        ReplayDltResponse replayResponse = new ReplayDltResponse(1, "inventory.public.products", "SUCCESS", "Replayed 1 message(s)");
        when(dltAdminService.replay(any())).thenReturn(replayResponse);

        mockMvc.perform(post("/api/v1/admin/dlt/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "offsets": [5],
                                    "correctedValue": "{\\"sku\\":\\"FIXED\\"}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.replayedCount").value(1));
    }
}
