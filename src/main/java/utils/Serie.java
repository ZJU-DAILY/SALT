package utils;

import java.util.ArrayDeque;
import java.util.Queue;

public class Serie {
    private String name = "";

    private Queue<Object> sourceQueue;

    public Serie(String name){
        this.name = name;
        this.sourceQueue = new ArrayDeque<>();
    }

    public void add(Object v) {
        sourceQueue.add(v);
    }

    public Queue<Object> getSourceQueue() {
        return sourceQueue;
    }
}
