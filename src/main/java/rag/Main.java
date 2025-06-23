
package rag;

// dashscope SDK的版本 >= 2.18.2
import java.util.Arrays;
import java.lang.System;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;

//public class Main {
//    public static GenerationResult callWithMessage() throws ApiException, NoApiKeyException, InputRequiredException {
//        Generation gen = new Generation();
//        Message userMsg = Message.builder()
//                .role(Role.USER.getValue())
//                .content("你是谁？")
//                .build();
//        GenerationParam param = GenerationParam.builder()
//                // 若没有配置环境变量，请用阿里云百炼API Key将下行替换为：.apiKey("sk-xxx")
//                .apiKey("sk-937378dfe8f04fc0925976e87638038f")
//                .model("deepseek-r1")
//                .messages(Arrays.asList(userMsg))
//                // 不可以设置为"text"
//                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
//                .build();
//        return gen.call(param);
//    }
//    public static void main(String[] args) {
//        try {
//            GenerationResult result = callWithMessage();
//            System.out.println("思考过程：");
//            System.out.println(result.getOutput().getChoices().get(0).getMessage().getReasoningContent());
//            System.out.println("回复内容：");
//            System.out.println(result.getOutput().getChoices().get(0).getMessage().getContent());
//        } catch (ApiException | NoApiKeyException | InputRequiredException e) {
//            // 使用日志框架记录异常信息
//            System.err.println("An error occurred while calling the generation service: " + e.getMessage());
//        }
//        System.exit(0);
//    }
//}
public class Main {
    public static GenerationResult callWithMessage(String input) throws ApiException, NoApiKeyException, InputRequiredException {
        Generation gen = new Generation();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(input)
                .build();
        GenerationParam param = GenerationParam.builder()
                .apiKey("sk-937378dfe8f04fc0925976e87638038f")
                .model("deepseek-r1")
                .messages(Arrays.asList(userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();
        return gen.call(param);
    }
}
