package service;

import util.Request;

public interface ServiceCallBack {
	
	// 服务端发送消息给客户端
	void send(Request request) throws Exception;
	
}
