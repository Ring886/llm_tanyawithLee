//package rag;
//
//import java.util.Arrays;
//
//import com.alibaba.dashscope.aigc.generation.Generation;
//import com.alibaba.dashscope.aigc.generation.GenerationParam;
//import com.alibaba.dashscope.aigc.generation.GenerationResult;
//import com.alibaba.dashscope.common.Message;
//import com.alibaba.dashscope.common.Role;
//import com.alibaba.dashscope.exception.ApiException;
//import com.alibaba.dashscope.exception.InputRequiredException;
//import com.alibaba.dashscope.exception.NoApiKeyException;
//
//import io.reactivex.Flowable;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//
//
//
////public class Main {
////    public static GenerationResult callWithMessage(String input) throws ApiException, NoApiKeyException, InputRequiredException {
////        Generation gen = new Generation();
////        Message userMsg = Message.builder()
////                .role(Role.USER.getValue())
////                .content(input)
////                .build();
////        GenerationParam param = GenerationParam.builder()
////                .apiKey("sk-937378dfe8f04fc0925976e87638038f")
////                .model("qwen-plus")
////                .messages(Arrays.asList(userMsg))
////                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
////                .build();
////        return gen.call(param);
////    }
////}
//
//
//
//public class Main {
//    private static final Logger logger = LoggerFactory.getLogger(Main.class);
//    private static final String API_KEY = "sk-937378dfe8f04fc0925976e87638038f";
//
//    /**
//     * 调用阿里云百炼大模型，流式返回结果并拼接为字符串。
//     * @param input 用户提问
//     * @return 大模型的完整回复文本
//     */
//    public static String streamCallWithMessage(String input)
//            throws NoApiKeyException, ApiException, InputRequiredException {
//        Generation gen = new Generation();
//        Message userMsg = Message.builder()
//                .role(Role.USER.getValue())
//                .content(input)
//                .build();
//
//        GenerationParam param = GenerationParam.builder()
//                .apiKey(API_KEY)
//                .model("qwen-plus")
//                .messages(Arrays.asList(userMsg))
//                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
//                .incrementalOutput(true)
//                .build();
//
//        StringBuilder fullContent = new StringBuilder();
//
//        Flowable<GenerationResult> result = gen.streamCall(param);
//        result.blockingForEach(message -> {
//            String delta = message.getOutput().getChoices().get(0).getMessage().getContent();
//            System.out.print(delta); // 实时输出片段
//            fullContent.append(delta);
//        });
//
//        return fullContent.toString();
//    }
//
//    public static void main(String[] args) {
//        try {
//            String result = streamCallWithMessage("你是谁？");
//            System.out.println("\n完整回复为: " + result);
//        } catch (Exception e) {
//            logger.error("调用失败: {}", e.getMessage());
//        }
//        System.exit(0);
//    }
//}


package rag;

import java.util.Arrays;
import java.util.function.Consumer;

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

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String API_KEY = "sk-937378dfe8f04fc0925976e87638038f";

    /**
     * 提供一个用于流式处理每段回复的方法，调用方传入 Consumer 即可自定义处理逻辑（如写入响应）。
     * @param input 提问文本
     * @param onDelta 每一个片段的处理逻辑（写入输出流）
     */
    public static void streamCallWithHandler(String input, Consumer<String> onDelta)
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

        Flowable<GenerationResult> result = gen.streamCall(param);
        result.blockingForEach(message -> {
            String delta = message.getOutput().getChoices().get(0).getMessage().getContent();
            System.out.print(delta); // 实时输出片段
            if (delta != null && !delta.isEmpty()) {
                onDelta.accept(delta);
            }
        });
    }

    /**
     * 用于控制台调试，可运行 main 体验流式回复效果。
     */
    public static void main(String[] args) {
        try {
            StringBuilder builder = new StringBuilder();
            streamCallWithHandler("你是谁？", delta -> {
                System.out.print(delta);         // 控制台流式打印
                builder.append(delta);           // 拼接最终内容
            });
            System.out.println("\n完整回复为: " + builder.toString());
        } catch (Exception e) {
            logger.error("调用失败: {}", e.getMessage());
        }
        System.exit(0);
    }
}






