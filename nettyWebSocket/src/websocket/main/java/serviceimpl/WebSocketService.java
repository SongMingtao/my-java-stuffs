package serviceimpl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.ServiceCallBack;
import util.CODE;
import util.Request;
import com.google.common.base.Strings;

public class WebSocketService implements ServiceCallBack {
	private static final Logger LOG = LoggerFactory.getLogger(WebSocketService.class);
	
	public static final Map<String, ServiceCallBack> webSocketWatchMap = new ConcurrentHashMap<String, ServiceCallBack>(); // <requestId, callBack>
	
	private ChannelHandlerContext ctx;
	private String name;
	
	public WebSocketService(ChannelHandlerContext ctx, String name) {
		this.ctx = ctx;
		this.name = name;
	}

	public static boolean register(String requestId, ServiceCallBack callBack) {
		if (Strings.isNullOrEmpty(requestId) || webSocketWatchMap.containsKey(requestId)) {
			return false;
		}
		webSocketWatchMap.put(requestId, callBack);
		return true;
	}
	
	public static boolean logout(String requestId) {
		if (Strings.isNullOrEmpty(requestId) || !webSocketWatchMap.containsKey(requestId)) {
			return false;
		}
		webSocketWatchMap.remove(requestId);
		return true;
	}
	

	public void send(Request request) throws Exception {
		if (this.ctx == null || this.ctx.isRemoved()) {
			throw new Exception("尚未握手建立链接成功，无法向客户端发送WebSocket消息");
		}
		this.ctx.channel().write(new TextWebSocketFrame(request.toJson()));
		this.ctx.flush();
	}
	
	
	/**
	 * 通知所有机器有机器相关的机器下线
	 * @param requestId
	 */
	public static void notifyDownline(String requestId) {
		WebSocketService.webSocketWatchMap.forEach((reqId, callBack) -> { // 通知有人下线
			Request serviceRequest = new Request();
			serviceRequest.setServiceId(CODE.downline.code);
			serviceRequest.setRequestId(requestId);
			try {
				callBack.send(serviceRequest);
			} catch (Exception e) {
				LOG.warn("回调发送消息给客户端异常", e);
			}
		});
	}
	
	public String getName() {
		return name;
	}

}
