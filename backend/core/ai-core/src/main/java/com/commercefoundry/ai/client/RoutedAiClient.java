package com.commercefoundry.ai.client;

import com.commercefoundry.ai.properties.AiProperties;

import java.util.Map;

/** Explicit provider routing; failures never select another provider. */
final class RoutedAiClient implements AiClient, AutoCloseable {
    private final AiProperties properties;
    private final Map<String, AiClient> clients;

    RoutedAiClient(AiProperties properties, Map<String, AiClient> clients) {
        this.properties = properties;
        this.clients = Map.copyOf(clients);
    }

    private AiClient client(String feature) {
        return clients.get(properties.providerFor(feature));
    }

    @Override

    public AiResult complete(AiRequest request) {
        return client(request.feature()).complete(request);
    }

    @Override

    public <T> AiStructuredResult<T> complete(AiRequest request, Class<T> type, AiOutputValidator<T> validator) {
        return client(request.feature()).complete(request, type, validator);
    }

    @Override

    public AiConversationResult converse(AiConversationRequest request) {
        return client(request.feature()).converse(request);
    }

    @Override

    public AiConversationResult converseStreaming(AiConversationRequest request, AiStreamListener listener) {
        return client(request.feature()).converseStreaming(request, listener);
    }

    @Override

    public void close() {
        clients.values().forEach(RoutedAiClient::closeClient);
    }

    static void closeClient(AiClient client) {
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                /* close every created transport */
            }
        }
    }
}
