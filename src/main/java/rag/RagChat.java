package rag;

public class RagChat {
    private final VectorStore store;

    public RagChat(VectorStore store) {
        this.store = store;
    }

    public String ask(String question) {
        String relevant = store.retrieveRelevant(question);
        String prompt = "以下是相关资料片段，请结合回答问题：\n" +
                relevant + "\n\n问题：" + question;

        try {
            return Main.callWithMessage(prompt)
                    .getOutput().getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            e.printStackTrace();
            return "调用大模型服务时出错：" + e.getMessage();
        }
    }
}
