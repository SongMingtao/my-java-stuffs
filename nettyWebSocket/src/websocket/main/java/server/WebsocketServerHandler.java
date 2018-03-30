package server;


import com.google.common.base.Strings;
import com.google.gson.JsonSyntaxException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.ServiceCallBack;
import serviceimpl.WebSocketService;
import util.CODE;
import util.Request;
import util.Response;

public class WebsocketServerHandler extends SimpleChannelInboundHandler<Object>{
    private static final Logger LOG = LoggerFactory.getLogger(WebsocketServerHandler.class);
    private WebSocketServerHandshaker handshaker;
    private ChannelHandlerContext ctx;
    private String sessionId;


    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if(msg instanceof FullHttpRequest){
            handleHttpRequest(ctx,(FullHttpRequest)msg);
        } else if (msg instanceof WebSocketFrame){
            handleWebSocketFrame(ctx, (WebSocketFrame)msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
//        super.channelReadComplete(ctx);
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//        super.exceptionCaught(ctx, cause);
        LOG.error("Websocket has a exception", cause);
        ctx.close();
        LOG.info(sessionId+" 已注销");
        WebSocketService.logout(sessionId);
        WebSocketService.notifyDownline(sessionId);
    }

    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        //如果http 解码失败 返回http异常
        if(!request.decoderResult().isSuccess() ||(!"websocket".equals(request.headers().get("Upgrade")))){
            sendHttpResponse(ctx, request, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
            return;
        }

        //Websocket http正常

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



    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame msg) throws Exception{
        //判断是否是关闭链路的指令
        if(msg instanceof CloseWebSocketFrame){
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) msg.retain());
            return;
        }
        //判断是否是Ping消息
        if (msg instanceof PingWebSocketFrame){
            ctx.channel().write(new PongWebSocketFrame(msg.content().retain()));
            return;
        }
        //当心只支持文本消息
        if (!(msg instanceof TextWebSocketFrame)){
            throw new UnsupportedOperationException("只支持文本消息");
        }
        //正常的话就处理
        try{
            Request request = Request.create(((TextWebSocketFrame)msg).text());
            Response response = new Response();
            response.setServiceId(request.getServiceId());
            if (CODE.online.code == request.getServiceId()){//客户端注册
                String requestId = request.getRequestId();

                if (Strings.isNullOrEmpty(requestId)){
                    response.setIsSucc(false).setMessage("requestId 不能为空");
                    return;
                } else if (Strings.isNullOrEmpty(request.getName())){
                    response.setIsSucc(false).setMessage("name不能为空");
                    return;
                } else if (WebSocketService.webSocketWatchMap.containsKey(requestId)){
                    response.setIsSucc(false).setMessage("您已经注册过了, 不能重复注册");
                    return;
                }

                if(!WebSocketService.register(requestId, new WebSocketService(ctx, request.getName()))){
                    response.setIsSucc(false).setMessage("注册失败");

                } else {
                    response.setIsSucc(true).setMessage("注册成功");

                    WebSocketService.webSocketWatchMap.forEach((reqId, callBack) -> {
                        response.getHadOnline().put(reqId, ((WebSocketService)callBack).getName()); // 将已经上线的人员返回

                        if (!reqId.equals(requestId)) {
                            Request serviceRequest = new Request();
                            serviceRequest.setServiceId(CODE.online.code);
                            serviceRequest.setRequestId(requestId);
                            serviceRequest.setName(request.getName());
                            try {
                                callBack.send(serviceRequest); // 通知有人上线
                            } catch (Exception e) {
                                LOG.warn("回调发送消息给客户端异常", e);
                            }
                        }
                    });
                }
                sendWebSocket(response.toJson());
                this.sessionId = sessionId;//记录会话id, 当页面刷新或者浏览器关闭的话吗注销他

            } else if (CODE.send_message.code == request.getServiceId()){ //客户端发消息到聊天群
                String requestId = request.getRequestId();
                if(Strings.isNullOrEmpty(requestId)){
                    response.setIsSucc(false).setMessage("requestId不能为空");
                } else if (Strings.isNullOrEmpty(request.getName())){
                    response.setIsSucc(false).setMessage("name不能为空");
                } else if (Strings.isNullOrEmpty(request.getMessage())){
                    response.setIsSucc(false).setMessage("message 不能为空");
                } else {
                    response.setIsSucc(true).setMessage("发送消息成功");

                    WebSocketService.webSocketWatchMap.forEach((reqId, callBack) -> { // 将消息发送到所有机器
                        Request serviceRequest = new Request();
                        serviceRequest.setServiceId(CODE.receive_message.code);
                        serviceRequest.setRequestId(requestId);
                        serviceRequest.setName(request.getName());
                        serviceRequest.setMessage(request.getMessage());
                        try {
                            callBack.send(serviceRequest);
                        } catch (Exception e) {
                            LOG.warn("回调发送消息给客户端异常", e);
                        }
                    });
                }
                sendWebSocket(response.toJson());

            }else if (CODE.downline.code == request.getServiceId()) { // 客户端下线
                String requestId = request.getRequestId();
                if (Strings.isNullOrEmpty(requestId)) {
                    sendWebSocket(response.setIsSucc(false).setMessage("requestId不能为空").toJson());
                } else {
                    WebSocketService.logout(requestId);
                    response.setIsSucc(true).setMessage("下线成功");

                    WebSocketService.notifyDownline(requestId); // 通知有人下线

                    sendWebSocket(response.toJson());
                }

            } else {
                sendWebSocket(response.setIsSucc(false).setMessage("未知请求").toJson());
            }
        } catch (JsonSyntaxException e1) {
            LOG.warn("Json解析异常", e1);
        } catch (Exception e2) {
            LOG.error("处理Socket请求异常", e2);
        }
    }

    /**
     * Http返回
     * @param ctx
     * @param request
     * @param response
     */
    private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        // 返回应答给客户端
        if (response.status().code() != 200) {

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

    public void sendWebSocket(String msg) throws Exception {
        if (this.handshaker == null || this.ctx == null || this.ctx.isRemoved()) {
            throw new Exception("尚未握手成功，无法向客户端发送WebSocket消息");
        }
        this.ctx.channel().write(new TextWebSocketFrame(msg));
        this.ctx.flush();
    }

}
