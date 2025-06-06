package net.rsprot.protocol.api

import com.github.michaelbull.logging.InlineLogger
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.handler.timeout.IdleStateHandler
import net.rsprot.protocol.api.logging.networkLog
import net.rsprot.protocol.api.login.LoginChannelHandler
import net.rsprot.protocol.api.login.LoginMessageDecoder
import net.rsprot.protocol.api.login.LoginMessageEncoder
import java.util.concurrent.TimeUnit

/**
 * The channel initializer for login blocks.
 * This initializer will add the login channel handler as well as an
 * idle state handler to ensure the connections are cut short if they go idle.
 */
public class LoginChannelInitializer<R>(
    private val networkService: NetworkService<R>,
) : ChannelInitializer<Channel>() {
    override fun initChannel(ch: Channel) {
        networkLog(logger) {
            "Channel initialized: $ch"
        }
        networkService.trafficMonitor.incrementConnections()
        ch.pipeline().addLast(
            IdleStateHandler(
                true,
                NetworkService.INITIAL_TIMEOUT_SECONDS,
                NetworkService.INITIAL_TIMEOUT_SECONDS,
                NetworkService.INITIAL_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            ),
            LoginMessageDecoder(networkService),
            LoginMessageEncoder(networkService),
            LoginChannelHandler(networkService),
        )
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        networkService
            .exceptionHandlers
            .channelExceptionHandler
            .exceptionCaught(ctx, cause)
        val channel = ctx.channel()
        if (channel.isOpen) {
            channel.close()
        }
    }

    private companion object {
        private val logger: InlineLogger = InlineLogger()
    }
}
