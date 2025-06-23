package rag;

import java.util.*;
import java.io.IOException;

public class VectorStore {

    public static class Entry {
        public final String text;
        public final List<Float> vector;
        public Entry(String text, List<Float> vector) {
            this.text = text;
            this.vector = vector;
        }
    }

    public final List<Entry> entries = new ArrayList<>();

    public void addEntry(String text) {
        try {
            List<Float> vec = EmbeddingUtil.embed(text);
            entries.add(new Entry(text, vec));
        } catch (IOException e) {
            System.err.println("向量生成失败，文本跳过：" + text);
            e.printStackTrace();
        }
    }

    public String retrieveRelevant(String question) {
        List<Float> questionVec;
        try {
            questionVec = EmbeddingUtil.embed(question);
        } catch (IOException e) {
            System.err.println("问题向量生成失败: " + question);
            e.printStackTrace();
            return "抱歉，向量生成失败，无法回答问题。";
        }

        double maxSim = -1;
        String bestMatch = "未能找到相关内容";

        for (Entry entry : entries) {
            double sim = EmbeddingUtil.cosineSimilarity(entry.vector, questionVec);
            if (sim > maxSim) {
                maxSim = sim;
                bestMatch = entry.text;
            }
        }
        return bestMatch;
    }
}
