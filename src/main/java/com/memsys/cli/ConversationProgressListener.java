package com.memsys.cli;

@FunctionalInterface
public interface ConversationProgressListener {
    void onEvent(ConversationProgressEvent event);
}
