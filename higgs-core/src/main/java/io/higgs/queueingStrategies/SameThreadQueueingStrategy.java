package io.higgs.queueingStrategies;

import io.higgs.DecodedMessage;
import io.higgs.functional.Function1;
import io.netty.channel.ChannelHandlerContext;

/**
 * @author Courtney Robinson <courtney@crlog.info>
 */
public class SameThreadQueueingStrategy<T, IM> extends QueueingStrategy<T, IM> {
    public SameThreadQueueingStrategy(QueueingStrategy<T, IM> strategy) {
        super(strategy);
    }

    /**
     * Invokes all {@link Function1}s subscribed to this message's topic on the current thread
     * without buffering/queueing
     *
     * @param ctx the channel context
     * @param msg the message to queue
     */
    @Override
    public void enqueue(ChannelHandlerContext ctx, DecodedMessage<T, IM> msg) {
        invokeListeners(ctx, msg);
    }
}