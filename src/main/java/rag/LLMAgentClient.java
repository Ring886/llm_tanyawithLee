package rag;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;

import io.reactivex.Flowable; // RxJava Flowable，用于处理流式结果
import org.slf4j.Logger;
import org.slf4j.LoggerFactory; // 用于日志

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer; // 用于外部传入流式处理逻辑

public class LLMAgentClient {

    private static final Logger logger = LoggerFactory.getLogger(LLMAgentClient.class);
    // 您的API_KEY，建议从配置文件或环境变量中加载，避免硬编码
    private static final String API_KEY = "sk-937378dfe8f04fc0925976e87638038f"; // *** 请替换为您的通义千问API Key ***

    private final Generation gen; // DashScope Generation 实例

    public LLMAgentClient() {
        this.gen = new Generation(); // 初始化 DashScope Generation 实例
    }

    /**
     * 调用LLM获取响应。内部采用流式调用，并通过 Consumer 回调传递片段。
     * 这个方法严格模仿 Main.java 中的 streamCallWithHandler，不返回任何值。
     *
     * @param prompt 用户提问（或Agent的Prompt）
     * @param onDelta 每一个片段的处理逻辑（例如：收集片段到StringBuilder，或实时发送到前端）
     * @throws NoApiKeyException
     * @throws ApiException
     * @throws InputRequiredException
     * @throws IOException 如果流式处理过程中发生其他IO错误
     */
    public void getAgentResponse(String prompt, Consumer<String> onDelta)
            throws NoApiKeyException, ApiException, InputRequiredException, IOException {

        logger.debug("LLM Agent Client received prompt for streaming: \n{}", prompt);

        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt) // 将整个prompt作为用户消息内容
                .build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(API_KEY)
                .model("qwen-plus") // 使用您希望的Qwen模型，例如 "qwen-turbo", "qwen-plus", "qwen-max"
                .messages(Arrays.asList(userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE) // 确保返回Message格式
                .incrementalOutput(true) // 开启流式输出
                .build();

        Flowable<GenerationResult> result = gen.streamCall(param);
        try {
            result.blockingForEach(message -> {
                String delta = message.getOutput().getChoices().get(0).getMessage().getContent();
                // 这里不再有 System.out.print，因为 onDelta 负责处理所有输出
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta); // 将 LLM 生成的片段传递给消费者
                }
            });
            logger.debug("LLM Stream Call Completed.");
        } catch (Exception e) {
            // 捕获 blockingForEach 内部可能抛出的异常，并重新抛出为声明的异常类型
            if (e instanceof NoApiKeyException) {
                throw (NoApiKeyException) e;
            } else if (e instanceof ApiException) {
                throw (ApiException) e;
            } else if (e instanceof InputRequiredException) {
                throw (InputRequiredException) e;
            } else {
                // 对于其他 IOException 或运行时异常，包装为 IOException
                logger.error("LLM Stream Call failed inside blockingForEach: {}", e.getMessage(), e);
                throw new IOException("LLM Stream Call failed: " + e.getMessage(), e);
            }
        }
    }
}
    