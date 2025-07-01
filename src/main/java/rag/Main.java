package rag;

import java.util.Arrays;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;

import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;




//public class Main {
//    public static GenerationResult callWithMessage(String input) throws ApiException, NoApiKeyException, InputRequiredException {
//        Generation gen = new Generation();
//        Message userMsg = Message.builder()
//                .role(Role.USER.getValue())
//                .content(input)
//                .build();
//        GenerationParam param = GenerationParam.builder()
//                .apiKey("sk-937378dfe8f04fc0925976e87638038f")
//                .model("qwen-plus")
//                .messages(Arrays.asList(userMsg))
//                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
//                .build();
//        return gen.call(param);
//    }
//}



public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String API_KEY = "sk-937378dfe8f04fc0925976e87638038f";

    /**
     * 调用阿里云百炼大模型，流式返回结果并拼接为字符串。
     * @param input 用户提问
     * @return 大模型的完整回复文本
     */
    public static String streamCallWithMessage(String input)
            throws NoApiKeyException, ApiException, InputRequiredException {
        Generation gen = new Generation();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(input)
                .build();

        GenerationParam param = GenerationParam.builder()
                .apiKey(API_KEY)
                .model("qwen-plus")
                .messages(Arrays.asList(userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .incrementalOutput(true)
                .build();

        StringBuilder fullContent = new StringBuilder();

        Flowable<GenerationResult> result = gen.streamCall(param);
        result.blockingForEach(message -> {
            String delta = message.getOutput().getChoices().get(0).getMessage().getContent();
            System.out.print(delta); // 实时输出片段
            fullContent.append(delta);
        });

        return fullContent.toString();
    }

    public static void main(String[] args) {
        try {
            String result = streamCallWithMessage("你是谁？");
            System.out.println("\n完整回复为: " + result);
        } catch (Exception e) {
            logger.error("调用失败: {}", e.getMessage());
        }
        System.exit(0);
    }
}
