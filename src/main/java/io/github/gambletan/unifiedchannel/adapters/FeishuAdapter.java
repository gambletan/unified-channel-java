package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Feishu (Lark) adapter stub.
 * <p>
 * TODO: Implement using the Feishu Open Platform API.
 * - Subscribe to message events via Event Subscription
 * - Send messages via POST /open-apis/im/v1/messages
 * - Support text, post (rich text), image, interactive card messages
 * - Handle tenant_access_token refresh (expires every 2 hours)
 * - Support group chats and 1:1 conversations
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/im-v1/message/create">Feishu API</a>
 */
public final class FeishuAdapter extends AbstractAdapter {

    private final String appId;
    private final String appSecret;

    public FeishuAdapter(String appId, String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
    }

    @Override public String channelId() { return "feishu"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Feishu adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Feishu send not yet implemented"));
    }
}
