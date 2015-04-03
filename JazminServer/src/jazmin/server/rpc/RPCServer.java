/**
 * 
 */
package jazmin.server.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import jazmin.core.Jazmin;
import jazmin.core.Server;
import jazmin.core.aop.DispatcherCallbackAdapter;
import jazmin.core.app.AppException;
import jazmin.log.Logger;
import jazmin.log.LoggerFactory;
import jazmin.misc.InfoBuilder;
import jazmin.misc.NetworkTrafficStat;
import jazmin.server.console.ConsoleServer;
import jazmin.server.rpc.RPCMessage.AppExceptionMessage;
import jazmin.server.rpc.codec.fst.FSTDecoder;
import jazmin.server.rpc.codec.fst.FSTEncoder;
import jazmin.server.rpc.codec.json.JSONDecoder;
import jazmin.server.rpc.codec.json.JSONEncoder;
import jazmin.server.rpc.codec.zjson.CompressedJSONDecoder;
import jazmin.server.rpc.codec.zjson.CompressedJSONEncoder;

/**
 * @author yama
 * 23 Dec, 2014
 */
public class RPCServer extends Server{
	private static Logger logger=LoggerFactory.get(RPCServer.class);
	//
	private int port=6001;
	private int idleTime=60*60;//one hour
	private String credential;
	private ServerBootstrap nettyServer;
	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private RPCServerHandler rpcServerHandler;
	private Map<String,Object>instanceMap;
	private Map<String,Method>methodMap;
	private Map<String,RPCSession>sessionMap;
	private Map<String,List<RPCSession>>topicSessionMap;
	private NetworkTrafficStat networkTrafficStat;
	private Set<String>acceptRemoteHosts;
	//
	public static final int CODEC_JSON=1;
	public static final int CODEC_ZJSON=2;
	public static final int CODEC_FST=3;
	public static int codec=CODEC_ZJSON;
	//
	public RPCServer() {
		nettyServer=new ServerBootstrap();
		instanceMap=new ConcurrentHashMap<String, Object>();
		methodMap=new ConcurrentHashMap<String, Method>();
		sessionMap=new ConcurrentHashMap<String, RPCSession>();
		topicSessionMap=new ConcurrentHashMap<String, List<RPCSession>>();
		acceptRemoteHosts=Collections.synchronizedSet(new TreeSet<String>());
		networkTrafficStat=new NetworkTrafficStat();
	}
	//--------------------------------------------------------------------------
	//instance
	/**
	 * register remote service
	 */
	public void registerService(RemoteService instance){
		Class<?>interfaceClass=null;
		for(Class<?>cc:instance.getClass().getInterfaces()){
			if(RemoteService.class.isAssignableFrom(cc)){
				//interface is subclass of RemoteService
				interfaceClass=cc;
			}
		}
		String instanceName=interfaceClass.getSimpleName();
		if(instanceMap.containsKey(instanceName)){
			throw new IllegalArgumentException("instance:"+instanceName
					+" already exites.");
		}
		logger.debug("register instance:{}",instanceName);
		instanceMap.put(instanceName, instance);
		//
		Class<?>implClass=instance.getClass();
		//
		for(Method m:implClass.getDeclaredMethods()){
			//Transaction annotation add on impl class so we should use implClass
			//
			String methodName=interfaceClass.getSimpleName()+"."+m.getName();
			if(methodMap.containsKey(methodName)){
				throw new IllegalArgumentException("method:"+methodName
						+" already exists.");
			}
			logger.debug("register method:{}",methodName);
			methodMap.put(methodName, m);
		}
	}
	
	//
	/** 
	 *  return all services name.
	 */
	public List<String>serviceNames(){
		return new ArrayList<String>(methodMap.keySet());
	}
	/**
	 * 
	 */
	public List<String>topicNames(){
		return new ArrayList<String>(topicSessionMap.keySet());
	}
	//
	public List<RPCSession>topicSession(String name){
		return topicSessionMap.get(name);
	}
	//--------------------------------------------------------------------------
	//io
	private void initNettyConnector(){
		rpcServerHandler=new RPCServerHandler(this);
		//
		ChannelInitializer<SocketChannel> channelInitializer
			=new ChannelInitializer<SocketChannel>() {
			@Override
			protected void initChannel(SocketChannel sc) throws Exception {
				sc.pipeline().addLast("idleStateHandler",
						new IdleStateHandler(idleTime,idleTime,0));
				if(codec==CODEC_ZJSON){
					sc.pipeline().addLast(
							new CompressedJSONEncoder(networkTrafficStat), 
							new CompressedJSONDecoder(networkTrafficStat),
							rpcServerHandler);		
				}else if(codec==CODEC_JSON){
					sc.pipeline().addLast(
							new JSONEncoder(networkTrafficStat), 
							new JSONDecoder(networkTrafficStat),
							rpcServerHandler);
				}else if(codec==CODEC_FST){
					sc.pipeline().addLast(
							new FSTEncoder(networkTrafficStat), 
							new FSTDecoder(networkTrafficStat),
							rpcServerHandler);
				}else{
					throw new IllegalArgumentException("bad codec type:"+RPCServer.codec);
				}
			}
		};
		//
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();
		nettyServer.group(bossGroup, workerGroup)
		.channel(NioServerSocketChannel.class)
		.option(ChannelOption.SO_BACKLOG, 128)    
		.option(ChannelOption.SO_SNDBUF, 1048576)    
		.option(ChannelOption.SO_RCVBUF, 1048576)  
		.option(ChannelOption.SO_REUSEADDR, true)   
        .childOption(ChannelOption.SO_KEEPALIVE, true) 
        .childOption(ChannelOption.TCP_NODELAY, true) 
        .childHandler(channelInitializer);
	}
	//--------------------------------------------------------------------------
	//message
	void messageReceived(RPCSession session,RPCMessage message){
		switch (message.type) {
		case RPCMessage.TYPE_RPC_CALL_REQ:
			rpcRequestCallMessageReceived(session,message);
			break;
		case RPCMessage.TYPE_SESSION_AUTH:
			authMessageReceived(session,message);
			break;
		default:
			logger.error("bad message type:"+message);
			break;
		}
	}
	private void authMessageReceived(RPCSession session,RPCMessage message){
		synchronized (session) {
			String principal=(String)message.payloads[0];
			String credential=(String)message.payloads[1];
			Boolean disablePush=(Boolean)message.payloads[2];
			Object topics[]=new Object[message.payloads.length-3];
			if(topics.length>0){
				System.arraycopy(message.payloads,3, topics, 0, topics.length);
			}
			//
			session.principal(principal);
			session.credential(credential);
			session.disablePushMessage(disablePush);
			for(Object o:topics){
				session.subscribe((String)o);
			}
			checkCredential(session);
			sessionCreated(session);
		}
	}
	//
	private void checkCredential(RPCSession session){
		if(this.credential==null){
			session.authed=true;
			return;
		}
		session.authed=credential.equals(session.credential);
	}
	//
	private void rpcRequestCallMessageReceived(RPCSession session,RPCMessage message){
		if(!session.authed){
			logger.error("session need credential:"+session);
			session.write(makeException(
					message.id,
					RPCMessage.TYPE_RPC_CALL_RSP,
					"need credential"));
			session.close();
			return;
		}
		//
		String serviceId=(String)message.payloads[0];
		Object args[]=new Object[message.payloads.length-1];
		if(args.length>0){
			System.arraycopy(message.payloads,1, args, 0, args.length);
		}
		String interfaceClass=serviceId.substring(0,serviceId.indexOf('.'));
		Object instance=instanceMap.get(interfaceClass);
		if(instance==null){
			logger.error("can not find instance:"+interfaceClass);
			session.write(makeException(
					message.id,
					RPCMessage.TYPE_RPC_CALL_RSP,
					"can not find instance:"+interfaceClass));
			return;
		}
		Method method=methodMap.get(serviceId);
		if(method==null){
			logger.error("can not find method:"+serviceId);
			session.write(makeException(
					message.id,
					RPCMessage.TYPE_RPC_CALL_RSP,
					"can not find method:"+serviceId));
			return;
		}
		RPCInvokeCallback callback=new RPCInvokeCallback(session,message);
		Jazmin.dispatcher.invokeInPool(
				"#"+message.id,
				instance, 
				method,
				callback, args);
	}
	//
	static class RPCInvokeCallback extends DispatcherCallbackAdapter{
		private RPCMessage message;
		private RPCSession session;
		public RPCInvokeCallback(RPCSession session,RPCMessage rpcMessage) {
			this.session=session;
			this.message=rpcMessage;
		}
		//
		@Override
		public void end(Object instance, Method method, Object[]args,Object ret, Throwable e) {
			RPCMessage rspMessage=new RPCMessage();
			rspMessage.id=message.id;
			rspMessage.type=RPCMessage.TYPE_RPC_CALL_RSP;
			if(e instanceof AppException){
				AppException ae=(AppException)e;
				AppExceptionMessage aem=new AppExceptionMessage();
				aem.code=ae.getCode();
				aem.message=ae.getMessage();
				rspMessage.payloads=new Object[]{ret,aem};
			}else{
				rspMessage.payloads=new Object[]{ret,e};	
			}
			session.write(rspMessage);
		}
	}
	//
	private RPCMessage makeException(int id,int type,String msg){
		RPCMessage rspMessage=new RPCMessage();
		rspMessage.id=id;
		rspMessage.type=type;
		rspMessage.payloads=new Object[]{null,new RPCException(msg)};	
		return rspMessage;
	}
	//
	/**
	 * broadcast to all session
	 */
	public void broadcast(String serviceId,Object payload){
		sessionMap.forEach((name,session)->{
			if(session.disablePushMessage){
				return;
			}
			RPCMessage msg=new RPCMessage();
			msg.type=RPCMessage.TYPE_PUSH;
			msg.payloads=new Object[]{serviceId,payload};
			session.write(msg);
		});
	}
	//
	public void publish(String topicId,Object payload){
		List<RPCSession>sessions=topicSessionMap.get(topicId);
		if(sessions==null){
			throw new IllegalArgumentException("can not find topic:"+topicId);
		}
		sessions.forEach(session->{
			if(session.disablePushMessage){
				return;
			}
			RPCMessage msg=new RPCMessage();
			msg.type=RPCMessage.TYPE_PUSH;
			msg.payloads=new Object[]{topicId,payload};
			session.write(msg);
		});
	}
	//
	//--------------------------------------------------------------------------
	//session
	void sessionCreated(RPCSession session){
		synchronized (session) {
			sessionCreated0(session);
		}
	}
	//
	void sessionCreated0(RPCSession session){
		if(session.principal==null){
			throw new IllegalStateException("session principal can not be null."+session);
		}
		RPCSession oldSession=sessionMap.get(session.principal());
		if(oldSession!=null){
			logger.warn("kick old rpc session:"+oldSession);
			oldSession.close();
		}
		if(logger.isInfoEnabled()){
			logger.info("rpc session created:"+session+"/topics:"+session.topics());
			
		}
		sessionMap.put(session.principal(),session);
		session.topics().forEach((topic)->{
			List<RPCSession>sessions=topicSessionMap.get(topic);
			if(sessions==null){
				sessions=(List<RPCSession>) Collections.synchronizedCollection(
						new ArrayList<RPCSession>());
				topicSessionMap.put(topic, sessions);
			}
			sessions.add(session);
		});
	}
	//
	void sessionDestroyed(RPCSession session){
		synchronized (session) {
			sessionDestroyed0(session);
		}
	}
	//
	void sessionDestroyed0(RPCSession session){
		if(session.principal==null){
			return;
		}
		sessionMap.remove(session.principal());
		session.topics().forEach((topic)->{
			List<RPCSession>sessions=topicSessionMap.get(topic);
			if(sessions!=null){
				sessions.remove(session);
			}
		});
	}
	//
	void checkSession(RPCSession session) {
		if(acceptRemoteHosts.isEmpty()){
			return;
		}
		if(!acceptRemoteHosts.contains(session.remoteHostAddress)){
			logger.warn("close session from unaccept remote host {}",
					session.remoteHostAddress);
			session.close();
		}
	}
	/**
	 * 
	 * @return
	 */
	public void addAcceptRemoteHost(String host){
		acceptRemoteHosts.add(host);
	}
	/**
	 * 
	 * @return
	 */
	public void removeAcceptRemoteHost(String host){
		acceptRemoteHosts.remove(host);
	}
	/**
	 * 
	 * @return
	 */
	public List<String>acceptRemoteHosts(){
		return new ArrayList<String>(acceptRemoteHosts);
	}
	/**
	 * 
	 * @return
	 */
	public String credential() {
		return credential;
	}
	/**
	 * 
	 * @param credential
	 */
	public void credential(String credential) {
		this.credential = credential;
	}
	/**
	 * @return the port
	 */
	public int port() {
		return port;
	}
	/**
	 * @param port the port to set
	 */
	public void port(int port) {
		checkServerState();
		this.port = port;
	}
	/**
	 * @return the idleTime
	 */
	public int idleTime() {
		return idleTime;
	}
	/**
	 * @param idleTime the idleTime to set
	 */
	public void idleTime(int idleTime) {
		checkServerState();
		this.idleTime = idleTime;
	}
	/**
	 * 
	 * @return
	 */
	public long inBoundBytes(){
		return networkTrafficStat.inBoundBytes.longValue();
	}
	/**
	 * 
	 * @return
	 */
	public long outBoundBytes(){
		return networkTrafficStat.outBoundBytes.longValue();
	}
	
	/**
	 * return all rpc session 
	 */
	public List<RPCSession>sessions(){
		return new ArrayList<RPCSession>(sessionMap.values());
	}
	//
	//
	private void checkServerState(){
		if(started()){
			throw new IllegalStateException("set before started");
		}
	}
	//--------------------------------------------------------------------------
	//lifecycle
	@Override
	public void init() throws Exception {
		initNettyConnector();
		//
		ConsoleServer cs=Jazmin.server(ConsoleServer.class);
		if(cs!=null){
			cs.registerCommand(new RPCServerCommand());
		}
	}
	//
	@Override
	public void start() throws Exception {
		nettyServer.bind(port).sync();
	}
	//
	@Override
	public void stop() throws Exception {
		if(bossGroup!=null){
			bossGroup.shutdownGracefully();
		}
		if(workerGroup!=null){
			workerGroup.shutdownGracefully();
		}
	}
	//
	@Override
	public String info() {
		InfoBuilder ib=InfoBuilder.create();
		ib.section("info")
		.format("%-30s:%-30s\n")
		.print("port",port)
		.print("credential",credential!=null)
		.print("idleTime",idleTime+" seconds");
		ib.section("accept hosts");
		int index=1;
		List<String>hosts=acceptRemoteHosts();
		Collections.sort(hosts);
		for(String s:hosts){
			ib.print(index++,s);
		}
		ib.section("services");
		index=1;
		List<String>methodNames=serviceNames();
		Collections.sort(methodNames);
		for(String s:methodNames){
			ib.print(index++,s);
		}
		return ib.toString();
	}
	
	
}
