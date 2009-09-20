//-----------------------------------------------------------
// LAJP-java(socket) (2009-09 http://code.google.com/p/lajp/)
// 
// Version: 9.09.01
// License: http://www.apache.org/licenses/LICENSE-2.0
//-----------------------------------------------------------

package lajpsocket;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单次请求服务线程
 * @author diaoyf
 *
 */
class SingleThread extends Thread
{
	/** PHP字符集 */
	final static String PHP_CHARSET = "UTF-8";
	/**
	 * 合法的request消息包最小长度
	 * 最小包格式： a:3:{i:0;i:1;i:1;i:1;i:2;s:17:"a:1:{i:0;s:0:"";}";}
	 */
	final static int REQUEST_MIN_LEN = 51;
	
	/** socket */
	Socket socket;
	
	/** 接收缓冲区 */
	byte[] recvBuff = new byte[16 * 1024];
	/** 接收缓冲区实际接收长度 */
	int recvCount;
	
	/** 参数字节数组 */
	byte[] args;
	int sp;
	
	/** 参数Node树(包含调用的类名和方法名称) */
	ArgsNode argsTree;
	
	/** 请求的类名 */
	String clazzName;
	/** 请求的方法名称 */
	String methodName;
	/** 参数类型数组 */
	Class<?>[] argsClazz;
	/** 参数值数组 */
	Object[] argsValue;
	
	/** 应答字节数组 */
	byte[] callBack = new byte[65536];
	/** 应答字节数组指针，指向数组中有效字节的下一个 */
	int cbp = 0;
	
	
	/**
	 * 构造方法
	 */
	public SingleThread(Socket socket)
	{
		this.socket = socket;
	}
	
	@Override
	public void run()
	{
		//--
		//System.out.println("线程启动");
		
		//---------------------------------------------------
		//	1.接收消息
		//---------------------------------------------------
		byte[] reqLenBuff = new byte[64];	
		
		try
		{
			InputStream inStream = socket.getInputStream();
			
			//接收第一次request，从中得到消息总长度
			recvCount = inStream.read(recvBuff, 0, recvBuff.length);
			if (recvCount < 3)
			{
				return;
			}
			
			//查找第一个","分割符
			int firstComma = 0;
			for (int i = 0; i < recvCount; i++)
			{
				if (recvBuff[i] == 0x2c)
				{
					firstComma = i;
					break;
				}
			}			
			//消息总长度
			int totalLen = Integer.parseInt(new String(recvBuff, 0, firstComma));
			
			//--
			//System.out.printf("消息总长度:%d,接收长度:%d\n", totalLen, recvCount);
			
			//初始化args
			args = new byte[totalLen];
			sp = 0;
			
			//复制缓冲区数据到args(第一次数据从","后开始)
			System.arraycopy(recvBuff, firstComma + 1, args, 0, recvCount - (firstComma + 1));
			sp += recvCount - (firstComma + 1);
			
			//接收剩余的消息
			while (sp < totalLen)
			{
				recvCount = inStream.read(recvBuff, 0, recvBuff.length);
				if (recvCount == -1)
				{
					break;
				}
				
				//复制缓冲区数据到args
				System.arraycopy(recvBuff, 0, args, sp, recvCount);
				sp += recvCount;
			}

		}
		catch (Exception e)
		{
			e.printStackTrace();
			try
			{
				socket.close();
			}
			catch (IOException e1)
			{
				e1.printStackTrace();
			}
			return;
		}		
		
		//--测试
		//System.out.println("request len:" + args.length);
		//System.out.println("request:" + new String(args));

		//---------------------------------------------------
		//	2.参数数量分析
		//---------------------------------------------------
		int a = nextIndex(args, (byte)0x3a, 0) + 1;		//第一个":"下一个
		int b = nextIndex(args, (byte)0x3a, a);			//第二个":"
		int argsCount = Integer.parseInt(new String(args, a, b-a));
		//初始化节点树跟节点(type=数组，name=null，value=参数数量)
		argsTree = ArgsNode.createNode("a", null, argsCount);
		//指针指向第一个子节点：调用java类和方法名称
		sp = nextIndex(args, (byte)0x7b, 0) + 1; //"{"的下一个
		
		//---------------------------------------------------
		//	3.解析参数数组,构建argsTree结构
		//---------------------------------------------------
		try
		{
			parseArgs(argsTree);
		}
		catch (Exception e)
		{
			//解析出错
			e.printStackTrace();
			
			//异常消息
			sendException("F" + "parse request message error: " + e.getMessage());		
			return;
		}

		//--测试参数数组解析结果
		//testArgsTree(argsTree, 0);
		
		
		//---------------------------------------------------
		//	4.解析ArgsTree结构,构建调用Java服务方法的：
		//		类名
		//		方法名
		//		方法参数类型数组
		//		方法参数值数组
		//---------------------------------------------------
		try
		{
			parseArgsTree();
		}
		catch (Exception e)
		{
			//解析出错
			e.printStackTrace();
			
			//异常消息
			sendException("F" + "parse request message error: " + e.getMessage());
			return;
		}

		//---------------------------------------------------
		//	5.调用Java服务方法
		//---------------------------------------------------
		//获得调用的方法
		Method method = null;
		try
		{
			method = ReflectUtil.matchingMethod(clazzName, methodName, argsClazz);
		}
		catch (ClassNotFoundException e)
		{
			e.printStackTrace();
			
			//异常消息
			sendException("F" + "ClassNotFoundException: " + e.getMessage());
			return;
		}
		catch (MethodNotFoundException e)
		{
			e.printStackTrace();

			//异常消息
			sendException("F" + "MethodNotFoundException: " + e.getMessage());
			return;
		}
		
		//调用
		Object obj = null; //方法返回
		try
		{
			obj = method.invoke(null, argsValue);
		}
		catch (IllegalArgumentException e)
		{
			e.printStackTrace();
			//异常消息
			sendException("F" + "IllegalArgumentException for call method " + clazzName + "." + method.getName());
			return;
		}
		catch (IllegalAccessException e)
		{
			e.printStackTrace();

			//异常消息
			sendException("F" + "IllegalAccessException for call method " + clazzName + "." + method.getName());
			return;
		}
		catch (InvocationTargetException e)
		{
			e.printStackTrace();

			//异常消息
			sendException("F" + "InvocationTargetException for call method " + clazzName + "." + method.getName());
			return;
		}
		
		//---------------------------------------------------
		//	6.转换Java服务方法返回值到Php序列化数据
		//---------------------------------------------------
		if (obj == null) //Java服务方法返回void
		{
			String rsp = "S" + "N";
			
			OutputStream outStream = null;
			try
			{
				outStream = socket.getOutputStream();
				outStream.write(rsp.getBytes(PHP_CHARSET));
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			finally
			{
				if (outStream != null)
				{
					try
					{
						outStream.close();
					}
					catch (IOException e)
					{
						e.printStackTrace();
					}
				}
				try
				{
					socket.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
			
			return;
		}
		else
		{
			try
			{
				javaSeriallze2Php(null, obj);
			}
			catch (Exception e)
			{
				e.printStackTrace();

				//异常消息
				sendException("F" + "Response message error: " + e.getMessage());
				return;
			}
			
			//--测试
			//System.out.println("response...:" + new String(callBack, 0, cbp));
		}
		
		//---------------------------------------------------
		//	7.构建并发送response消息包
		//---------------------------------------------------
		OutputStream outStream = null;
		try
		{
			outStream = socket.getOutputStream();
			outStream.write(0x53); //写标致位"S"
			outStream.write(callBack, 0, cbp);
			
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		finally
		{
			if (outStream != null)
			{
				try
				{
					outStream.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
			try
			{
				socket.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}

		//System.out.println("线程关闭");
	}
	
	/**
	 * 解析php序列化参数数组
	 * @param father 父节点(数组或对象)
	 */
	private void parseArgs(ArgsNode father) throws Exception
	{
		//处理所有的子节点
		NEXT: while (true)
		{
			//到结束了
			if (sp >= args.length)
			{
				return;
			}
			
			if (args[sp] == 0x7d)	//"}" 本层结束
			{
				sp++;
				break;
			}

			//下标-----------------------------------------------
			byte nameType = args[sp];	//"下标"类型
			String name = null;			//"下标"
			switch (nameType)
			{
				case 0x69: 		//i 整形
					int a = sp + 2;							//"下标"起始
					sp = nextIndex(args, (byte)0x3b, a); 	//"下标"结束";"
					sp++;									//"值"类型起始
					break;
				case 0x73:		//s 字符串
					a = sp + 2;								//"下标"长度起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"下标"长度结束":"
					int len = Integer.parseInt(new String(args, a, sp - a)); //"下标"长度
					a = sp + 2;								//"下标"起始(掠过引号)
					sp = a + len;							//"下标"结束(结束引号)
					name = new String(args, a, sp - a);		//"下标"
					sp = sp + 2;							//"值"类型起始			
					break;
				default:
					throw new Exception("index[" + nameType + "] must be 'i' or 's'");
			}

			//值-----------------------------------------------
			byte valueType = args[sp];	//"值"类型
			//类型
			switch (valueType)
			{
				case 0x4e:		//NULL
					father.addChild(ArgsNode.createNode("N", name, null));
					sp = sp + 2;							//下一个
					continue NEXT;
				case 0x69: 		//i 整形
					int a = sp + 2;							//"值"起始
					sp = nextIndex(args, (byte)0x3b, a); 	//"值"结束";"
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("i", name, Integer.parseInt(new String(args, a, sp - a))));
					sp = sp + 1;							//下一个
					continue NEXT;
				case 0x64:		//d 浮点
					a = sp + 2;								//"值"起始
					sp = nextIndex(args, (byte)0x3b, a); 	//"值"结束";"
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("d", name, Double.parseDouble(new String(args, a, sp - a))));
					sp = sp + 1;							//下一个
					continue NEXT;
				case 0x62:		//b 布尔
					a = sp + 2;								//"值"
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("b", name, args[a] == 0x31 ? true : false));
					sp = a + 2;								//下一个
					continue NEXT;
				case 0x73:		//s 字符串
					a = sp + 2;								//字符串长度起始
					sp = nextIndex(args, (byte)0x3a, a); 	//字符串长度结束":"
					int len = Integer.parseInt(new String(args, a, sp - a)); //字符串长度
					a = sp + 2;								//字符串起始(掠过引号)
					
					//ArgsNode对象
					father.addChild(ArgsNode.createNode("s", name, new String(args, a, len)));
					
					sp = a + len;							//字符串结束(结束引号)
					sp = sp + 2;							//下一个				
					continue NEXT;
				case 0x61:		//a 数组
					a = sp + 2;								//"数组长度"起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"数组长度"结束":"
					int arrayLen = Integer.parseInt(new String(args, a, sp -a));
					//ArgsNode对象(value=数组长度)
					ArgsNode arrayNode = ArgsNode.createNode("a", name, arrayLen);
					father.addChild(arrayNode);	
					sp = sp + 2;							//数组第一个元素(掠过{)				
					//递归处理子节点
					parseArgs(arrayNode);
					continue NEXT;
				case 0x4f:		//O 对象
					a = sp + 2;								//"对象类型长度"起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"对象类型长度"结束":"
					len = Integer.parseInt(new String(args, a, sp - a)); //"对象类型长度"长度
					a = sp + 2;								//"对象类型"起始(掠过引号)
					//ArgsNode对象(有值：类型)
					ArgsNode objNode = ArgsNode.createNode("O", name, new String(args, a, len));
					father.addChild(objNode);
					a = a + len + 2;						//"对象属性长度"起始
					sp = nextIndex(args, (byte)0x3a, a); 	//"对象属性长度"结束":"
					sp = sp + 2;							//对象第一个属性(掠过{)
					//递归处理子节点
					parseArgs(objNode);
					continue NEXT;
				default:
					throw new Exception("index[" + valueType + "] must be 'i','d','b','s','a','O'.");
			}

		}		
	}
	
	/**
	 * 解析ArgsTree结构，解析出调用Java的条件：
	 * <li>类名</li>
	 * <li>方法名</li>
	 * <li>方法参数类型数组</li>
	 * <li>方法参数值数组</li>
	 * @throws Exception 
	 */
	private void parseArgsTree() throws Exception
	{
		//获取调用类和方法名----------------------------------
		String clazzMethod = (String)argsTree.subList.get(0).Value;
		int coloncolonIndex = clazzMethod.indexOf("::");
		clazzName = clazzMethod.substring(0, coloncolonIndex);
		methodName = clazzMethod.substring(coloncolonIndex + 2);
		
		//--
		//System.out.printf("调用类名:%s,调用方法名:%s\n", clazzName, methodName);
		
		
		//初始化"方法参数类型数组"、"方法参数值数组"
		argsClazz = new Class[argsTree.subList.size() - 1];
		argsValue = new Object[argsTree.subList.size() - 1];

		
		//获取方法参数类型数组---------------------------------
		for (int i = 0; i < argsClazz.length; i++)
		{
			//当前节点
			ArgsNode currentNode = argsTree.subList.get(i + 1);
			//当前节点类型
			String type = currentNode.type;
			if (type.equals("N"))
			{
				argsClazz[i] = null;
			}
			else if (type.equals("i"))
			{
				argsClazz[i] = int.class;
			}
			else if (type.equals("d"))
			{
				argsClazz[i] = double.class;
			}
			else if (type.equals("b"))
			{
				argsClazz[i] = boolean.class;
			}
			else if (type.equals("s"))
			{
				argsClazz[i] = java.lang.String.class;
			}
			else if (type.equals("a"))
			{
				//以数据第一个元素的key类型为依据:
				//	如果是"i"(在ArgsNode节点中不存储name),对应java.util.List,
				//	如果是"s"(在ArgsNode节点中key存储在name中),对应java.util.Map。
				//如果数组长度为0，对应java.util.List
				if (currentNode.subList.size() > 0)
				{
					String subNodeName = currentNode.subList.get(0).name;
					if (subNodeName == null)
					{
						argsClazz[i] = java.util.List.class;
					}
					else
					{
						argsClazz[i] = java.util.Map.class;
					}
				}
				else
				{
					argsClazz[i] = java.util.List.class;
				}
			}
			else if (type.equals("O"))
			{
				//类名
				String phpClazzName = (String)currentNode.Value;
				String javaClazzName = phpClazzName.replace('_', '.');
				//将"-"替换为"."
				try
				{
					argsClazz[i] = Class.forName(javaClazzName);
				}
				catch (ClassNotFoundException e)
				{
					//解析出错
					e.printStackTrace();
					throw new Exception("Can't find Class " + javaClazzName + " in Java.");
					
				}
			}
		}
		
		//获取方法参数值数组---------------------------------
		for (int i = 0; i < argsValue.length; i++)
		{
			argsValue[i] = parseArgsNodeValue(argsTree.subList.get(i + 1));
		}
		
		
	}
	
	/**
	 * 将ArgsNode节点及其子节点解析为java对象
	 * @param node 要解析的节点
	 * @return 解析出的java对象
	 */
	private Object parseArgsNodeValue(ArgsNode node) throws Exception
	{
		if (node.type.equals("N"))
		{
			return null;
		}
		else if (node.type.equals("i"))
		{
			return (Integer)node.Value;
		}
		else if (node.type.equals("d"))
		{
			return (Double)node.Value;
		}
		else if (node.type.equals("b"))
		{
			return (Boolean)node.Value;
		}
		else if (node.type.equals("s"))
		{
			return (String)node.Value;
		}
		else if (node.type.equals("a"))
		{
			//以数据第一个元素的key类型为依据:
			//	如果是"i"(在ArgsNode节点中不存储name),对应java.util.List,
			//	如果是"s"(在ArgsNode节点中key存储在name中),对应java.util.Map。
			//如果数组长度为0，对应java.util.List
			if (node.subList.size() > 0)
			{
				String subNodeName = node.subList.get(0).name;
				if (subNodeName == null)
				{
					List<Object> list = new ArrayList<Object>();
					for (ArgsNode subNode : node.subList)
					{
						list.add(parseArgsNodeValue(subNode));
					}
					
					return list;
				}
				else
				{
					Map<String, Object> map = new HashMap<String, Object>();
					for (ArgsNode subNode : node.subList)
					{
						map.put(subNode.name, parseArgsNodeValue(subNode));
					}
					
					return map;
				}
			}
			else
			{
				return new ArrayList<Object>();
			}
		}
		else if (node.type.equals("O"))
		{
			//类名
			String phpClazzName = (String)node.Value;
			String javaClazzName = phpClazzName.replace('_', '.');
			try
			{
				//实例化对象
				Object retObj = Class.forName(javaClazzName).newInstance();
				//对象有属性
				if (node.subList.size() > 0)
				{
					//设置属性
					for (ArgsNode subNode : node.subList)
					{
						//属性:基本数据类型
						if (subNode.type.equals("i") || subNode.type.equals("d")
								|| subNode.type.equals("b") || subNode.type.equals("s"))
						{
							//--
							//System.out.printf("%s,%s,%s\n", retObj.getClass(), subNode.name, subNode.Value);
							
							javaBeanSetXXX(retObj, subNode.name, subNode.Value);
						}
						//属性:集合或对象
						else if (subNode.type.equals("a") || subNode.type.equals("O"))
						{
							javaBeanSetXXX(retObj, subNode.name, parseArgsNodeValue(subNode));
						}
					}
					
				}
				
				return retObj;
			}
			catch (InstantiationException e)
			{
				e.printStackTrace();
				throw new Exception("Can't create instantiation of Class: " + javaClazzName);
			}
			catch (IllegalAccessException e)
			{
				e.printStackTrace();
				throw new Exception("Illega access of Class: " + javaClazzName);
			}
			catch (ClassNotFoundException e)
			{
				e.printStackTrace();
				throw new Exception("Can't find class: " + javaClazzName);
			}
		}
		else 
		{
			return null;
		}
	}
	
	/**
	 * 设置javaBean对象属性
	 * @param javaBean javaBean对象
	 * @param attributeName 属性名称
	 * @param value 值
	 * @throws Exception
	 */
	private void javaBeanSetXXX(
			Object javaBean, 
			String attributeName, 
			Object value) throws Exception 
	{
		BeanInfo beanInfo;
		try
		{
			beanInfo = Introspector.getBeanInfo(javaBean.getClass(), Object.class);
		}
		catch (IntrospectionException e)
		{
			//内省异常
			e.printStackTrace();
			throw new Exception("IntrospectionException for " + javaBean.getClass());
		}
		
		//获得javaBean属性集
		PropertyDescriptor[] pds = beanInfo.getPropertyDescriptors();
		
		for (PropertyDescriptor pd : pds) 
		{
			if(pd.getName().equals(attributeName))
			{
				//--
				//System.out.printf("pd.getName():%s,attributeName:%s,%b\n", pd.getName(), attributeName, pd.getName().equals(attributeName));
				
				try
				{
					Method method = pd.getWriteMethod();
					//--
					//System.out.printf("反射set方法:%s,对象:%s,属性:%s,值类型:%s,值:%s\n", method.getName(), javaBean.getClass(), attributeName, value.getClass(), value);
					//执行set方法
					method.invoke(javaBean, value);
					break;
				}
				catch (IllegalArgumentException e)
				{
					e.printStackTrace();
					throw new Exception("IllegalArgumentException for " + javaBean.getClass() + "." + attributeName);
				}
				catch (IllegalAccessException e)
				{
					e.printStackTrace();
					throw new Exception("IllegalAccessException for " + javaBean.getClass() + "." + attributeName);
				}
				catch (InvocationTargetException e)
				{
					e.printStackTrace();
					throw new Exception("InvocationTargetException for " + javaBean.getClass() + "." + attributeName);
				}
			}
		}
	}
	
	/**
	 * java服务方法返回值转换为php序列化数据，并以callBack存储。
	 * @param name 对应数组的key或对象的属性名,如果null则无key或属性名
	 * @param obj
	 */
	private void javaSeriallze2Php(Object name, Object obj) throws Exception
	{
		//名--------------------
		if (name != null)
		{
			if (name instanceof Integer)
			{
				//数组下标
				int index = (Integer)name;
				String nn = "i:" + index + ";"; 
				
				try
				{
					copy2CallBack(nn.getBytes(PHP_CHARSET));
				}
				catch (UnsupportedEncodingException e)
				{
					//此异常不可能发生，忽略
					e.printStackTrace();
				}
			}
			else if (name instanceof String)
			{
				//数组key
				String key = (String)name;
				byte[] key_b = null;
				try
				{
					key_b = key.getBytes(PHP_CHARSET);
				}
				catch (UnsupportedEncodingException e)
				{
					//此异常不可能发生，忽略
					e.printStackTrace();
				}
				
				StringBuilder nn = new StringBuilder();
				nn.append("s:");
				nn.append(key_b.length);
				nn.append(":\"");
				nn.append(key);
				nn.append("\";");
				
				try
				{
					copy2CallBack(nn.toString().getBytes(PHP_CHARSET));
				}
				catch (UnsupportedEncodingException e)
				{
					//此异常不可能发生，忽略
					e.printStackTrace();
				}
			}
		}

		//值-------------------------------
		if (obj == null)
		{
			byte[] nullValue = new byte[2];
			nullValue[0] = 0x4e;	//N
			nullValue[1] = 0x3b;	//;
			copy2CallBack(nullValue);
		}
		else if (obj instanceof Integer)
		{			
			int i = (Integer)obj;
			String vv = "i:" + i + ";"; 
			try
			{
				copy2CallBack(vv.getBytes(PHP_CHARSET));
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}
		}
		else if (obj instanceof Double)
		{
			double d = (Double)obj;
			String vv = "d:" + d + ";"; 
			try
			{
				copy2CallBack(vv.getBytes(PHP_CHARSET));
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}
		}
		else if (obj instanceof Boolean)
		{
			boolean b = (Boolean)obj;
			String vv = "b:" + (b ? "1" : "0") + ";"; 
			try
			{
				copy2CallBack(vv.getBytes(PHP_CHARSET));
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}
		}
		else if (obj instanceof String)
		{
			String s = (String)obj;
			try
			{
				//值
				byte[] sByte = null;
				if (s == null) //null当作长度为0的字符串
				{
					sByte = new byte[0];
				}
				else
				{
					sByte = s.getBytes(PHP_CHARSET);
				}
				
				//值长度
				byte[] sByteLen = ("" + sByte.length).getBytes(PHP_CHARSET);
				
				byte[] vv = new byte[2 + sByteLen.length + 2 + sByte.length + 2];
				vv[0] = 0x73; 											//s
				vv[1] = 0x3a;											//:
				System.arraycopy(sByteLen, 0, vv, 2, sByteLen.length);	//长度
				vv[2 + sByteLen.length] = 0x3a;							//:
				vv[2 + sByteLen.length + 1] = 0x22;						//"
				System.arraycopy(sByte, 0, vv, 2 + sByteLen.length + 2, sByte.length);
				vv[2 + sByteLen.length + 2 + sByte.length] = 0x22;		//"
				vv[2 + sByteLen.length + 2 + sByte.length + 1] = 0x3b;	//;
				
				copy2CallBack(vv);
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}
		}
		else if (obj instanceof List)
		{
			List<?> list = (List<?>)obj;
			//数组长度
			byte[] arrayLen = null;
			try
			{
				arrayLen = ("" + list.size()).getBytes(PHP_CHARSET);
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}
			
			//数组前部
			byte[] vv1 = new byte[2 + arrayLen.length + 2]; 
			vv1[0] = 0x61;											//a
			vv1[1] = 0x3a;											//:
			System.arraycopy(arrayLen, 0, vv1, 2, arrayLen.length);	//数组
			vv1[2 + arrayLen.length] = 0x3a;						//:
			vv1[2 + arrayLen.length + 1] = 0x7b;					//{
			copy2CallBack(vv1);
			
			//数组元素
			for (int i = 0; i < list.size(); i++)
			{
				Object subObj = list.get(i);
				javaSeriallze2Php(i, subObj);
			}
			
			//数组后部
			byte[] vv2 = new byte[]{0x7d};							//}
			copy2CallBack(vv2);
		}
		else if (obj instanceof Map)
		{
			Map<?, ?> map = (Map<?, ?>)obj;
			Set<?> keySet = map.keySet();
			
			//数组长度
			byte[] arrayLen = null;
			try
			{
				arrayLen = ("" + keySet.size()).getBytes(PHP_CHARSET);
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}
			
			//数组前部
			byte[] vv1 = new byte[2 + arrayLen.length + 2]; 
			vv1[0] = 0x61;											//a
			vv1[1] = 0x3a;											//:
			System.arraycopy(arrayLen, 0, vv1, 2, arrayLen.length);	//数组
			vv1[2 + arrayLen.length] = 0x3a;						//:
			vv1[2 + arrayLen.length + 1] = 0x7b;					//{
			copy2CallBack(vv1);
			
			//数组元素
			Iterator<?> iterator = keySet.iterator();
			for (; iterator.hasNext(); )
			{
				String key = (String)iterator.next();
				javaSeriallze2Php(key, map.get(key));
			}
			
			//数组后部
			byte[] vv2 = new byte[]{0x7d};							//}
			copy2CallBack(vv2);
		}
		else //JavaBean
		{
			Class<?> clazz = obj.getClass(); //所属类
			byte[] className_b = null;
			try
			{
				className_b = clazz.getName().getBytes(PHP_CHARSET);
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}
			
			PropertyDescriptor[] pd = null; //属性描述
			try
			{
				BeanInfo benaInfo = Introspector.getBeanInfo(clazz, Object.class);
				pd = benaInfo.getPropertyDescriptors();
			}
			catch (IntrospectionException e)
			{
				e.printStackTrace();				
				throw new Exception("IntrospectionException for class " + clazz.getName());
			}

			//对象前部
			StringBuilder oo1 = new StringBuilder();
			oo1.append("O:");
			oo1.append(className_b.length);
			oo1.append(":\"");
			oo1.append(clazz.getName().replace('.', '_'));
			oo1.append("\":");
			oo1.append(pd.length);
			oo1.append(":{");
			try
			{
				copy2CallBack(oo1.toString().getBytes(PHP_CHARSET));
			}
			catch (UnsupportedEncodingException e)
			{
				//此异常不可能发生，忽略
				e.printStackTrace();
			}

			//对象属性
			for (int i = 0; i < pd.length; i++)
			{
				try
				{
					javaSeriallze2Php(pd[i].getName(), pd[i].getReadMethod().invoke(obj, null));
				}
				catch (IllegalArgumentException e)
				{
					e.printStackTrace();				
					throw new Exception("IllegalArgumentException for attribute " + pd[i].getName() + " from class " + clazz.getName());
				}
				catch (IllegalAccessException e)
				{
					e.printStackTrace();				
					throw new Exception("IllegalAccessException for attribute " + pd[i].getName() + " from class " + clazz.getName());
				}
				catch (InvocationTargetException e)
				{
					e.printStackTrace();				
					throw new Exception("InvocationTargetException for attribute " + pd[i].getName() + " from class " + clazz.getName());
				}
			}

			//数组后部
			byte[] oo2 = new byte[]{0x7d};							//}
			copy2CallBack(oo2);

		}
	}
	
	/**
	 * 将参数cc复制到callBack尾部，如果callBack长度不够扩展一倍
	 * @param cc 要复制的数据
	 */
	private void copy2CallBack(byte[] cc)
	{
		//callBack长度自动变化
		while (cc.length + cbp > callBack.length)
		{
			//创建新的callBacl数组，长度是原callBack的一倍
			byte[] newCallBack = new byte[callBack.length * 2];
			
			//复制原callBack数据到新的callBacl数组
			System.arraycopy(callBack, 0, newCallBack, 0, cbp);
			callBack = newCallBack;
		}
		
		//复制cc到callBack尾部
		System.arraycopy(cc, 0, callBack, cbp, cc.length);
		//cbp指针
		cbp += cc.length;
	}

	/**
	 * 在"buf"数组中查找下一个"c", 从start开始
	 * @param buf
	 * @param c
	 * @param start
	 * @return 返回下标，如果查询不到返回-1
	 */
	private int nextIndex(byte[] buf, byte c, int start)
	{
		for (int index = start; index < buf.length; index++)
		{
			if (buf[index] == c)
			{
				return index;
			}
		}
		
		return -1;
	}
	

	

	
	/**
	 * 测试
	 * @param node
	 * @param tab 缩进层级
	 */
	private void testArgsTree(ArgsNode node, int tab)
	{
		String tabCount = "";
		for (int i = 0; i < tab; i++)
		{
			tabCount += "\t";
		}
		
		//
		System.out.printf("%stype=%s,name=%s,value=%s\n", tabCount, node.type, node.name, node.Value);

		if (node.type.equals("a") || node.type.equals("O"))
		{
			for (ArgsNode subNode : node.subList)
			{
				testArgsTree(subNode, tab + 1);
			}
		}
	}
	
	/**
	 * 发送异常信息
	 * @param msg
	 */
	private void sendException(String msg)
	{
		OutputStream outStream = null;
		try
		{
			outStream = socket.getOutputStream();
			outStream.write(msg.getBytes(PHP_CHARSET));
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		finally
		{
			if (outStream != null)
			{
				try
				{
					outStream.close();
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
			try
			{
				socket.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
		

	}
}