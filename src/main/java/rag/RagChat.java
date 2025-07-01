package rag;

public class RagChat {
    private final VectorStore store;

    public RagChat(VectorStore store) {
        this.store = store;
    }




    // 新添加
    public void streamAsk(String question, java.util.function.Consumer<String> onDelta) {
        String relevant = store.retrieveRelevant(question);
        String prompt = "以下是相关资料片段，请结合回答问题。如果用户发送的文本没有命中本地知识库，则无需按照本地知识库回答。：\n" + relevant + "\n\n问题：" + question;

        try {
            Main.streamCallWithHandler(prompt, onDelta);
        } catch (Exception e) {
            onDelta.accept("出错：" + e.getMessage());
        }
    }




//    public String ask(String question) {
//        String relevant = store.retrieveRelevant(question);
//        String prompt = "以下是相关资料片段，请结合回答问题：\n" +
//                relevant + "\n\n问题：" + question;
//
//        try {
////            return Main.callWithMessage(prompt)
////                    .getOutput().getChoices().get(0).getMessage().getContent();
//            return Main.streamCallWithMessage(question);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            return "调用大模型服务时出错：" + e.getMessage();
//        }
//    }
}