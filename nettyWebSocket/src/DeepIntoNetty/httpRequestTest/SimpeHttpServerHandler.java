import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.CharsetUtil;
import jdk.internal.org.xml.sax.ContentHandler;

public class SimpeHttpServerHandler extends SimpleChannelInboundHandler<Object> {

    private ChannelHandlerContext ctx;
    private WebSocketServerHandshaker handshaker;
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
//        System.out.print("get msg is:\n" +msg);
//        System.out.print("ctx is :\n" + ctx);
        if (msg instanceof FullHttpRequest){
            System.out.print("Http msg");
            handleFullHttpRequest(ctx, (FullHttpRequest)msg);
        } else if (msg instanceof WebSocketFrame){
            System.out.println("websocketFrame called \n");
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        System.err.println("channel read Complete");
//        super.channelReadComplete(ctx);
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

    private void handleFullHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        System.out.println(request.decoderResult());
        System.out.println(request.decoderResult().isSuccess());

        //如果http 解码失败 返回http异常
        if(!request.decoderResult().isSuccess() ||(!"websocket".equals(request.headers().get("Upgrade")))){
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            return;
        }


        WebSocketServerHandshakerFactory wsFaxtory = new WebSocketServerHandshakerFactory("ws://"+request.headers().get(
                HttpHeaderNames.HOST), null, false);
         handshaker = wsFaxtory.newHandshaker(request);
        if(handshaker == null){//无法处理的websock版本
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            //向客户端发送websocket握手完成握手
            handshaker.handshake(ctx.channel(), request);
            //记录管道处理上下文 便于服务器推送数据到客户端
            this.ctx = ctx;
        }

    }

    private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest request, DefaultFullHttpResponse response) {
        // 返回应答给客户端
        System.out.println("code "+response.status().code());
        if (response.status().code() != 200) {
            System.out.println("send Http Response if called");
            ByteBuf buf = Unpooled.copiedBuffer(response.status().toString(), CharsetUtil.UTF_8);
            response.content().writeBytes(buf);
            buf.release();
//            HttpHeaders.setContentLength(response, response.content().readableBytes());
            HttpUtil.setContentLength(response, response.content().readableBytes());
        }

        // 如果是非Keep-Alive，关闭连接
        ChannelFuture f = ctx.channel().writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request) || response.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

}
