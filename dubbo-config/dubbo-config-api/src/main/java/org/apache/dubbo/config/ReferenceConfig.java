/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.AsyncFor;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.AvailableCluster;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * ReferenceConfig
 *
 * @export
 */
public class ReferenceConfig<T> extends AbstractReferenceConfig {

    private static final long serialVersionUID = -5864351140409987595L;

    private static final Protocol refprotocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private final List<URL> urls = new ArrayList<URL>();
    // interface name
    private String interfaceName;
    private Class<?> interfaceClass;
    private Class<?> asyncInterfaceClass;
    // client type
    private String client;
    // url for peer-to-peer invocation
    // 对等调用的url
    private String url;
    // method configs
    private List<MethodConfig> methods;
    // default config
    private ConsumerConfig consumer;
    private String protocol;
    // interface proxy reference
    private transient volatile T ref;
    private transient volatile Invoker<?> invoker;
    private transient volatile boolean initialized;
    private transient volatile boolean destroyed;
    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        appendAnnotation(Reference.class, reference);
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    public synchronized T get() {
        // 已经销毁则不能再获取
        if (destroyed) {
            throw new IllegalStateException("Already destroyed!");
        }
        // 如果ref为空则初始化后再返回
        if (ref == null) {
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    private void init() {
        // 先判断initialized标记，如果为true，表示已经初始化过，初始化过则不再初始化。
        // 从这里看出如果第一次初始化失败了，则后续该consumer无法再使用
        if (initialized) {
            return;
        }
        initialized = true;
        //检查接口是否定义过了
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        // 再次检查consumer配置，同时通过appendProperties修改代码/xml中的配置。
        // appendProperties从System.getProperty中获取配置，如果有相应值则替换配置文件中的值。
        // 比如对于consumer来说，如果配置了timeout=5000, 可以通过：
        // 1、在启动参数中设置dubbo.consumer.timeout=3000来修改这个值；
        // 2、在启动参数中设置dubbo.consumer.com.alibaba.dubbo.config.ConsumerConfig.timeout=3000来修改这个值。
        // 其中第二种方式优先级高于第一种，修改其他属性只需要替换"timeout"。
        // 如果系统设置中没有，还可以在启动参数中设置dubbo.properties.file（如果没设置则默认为dubbo.properties）, 加载文件中的配置
        // 其中系统设置的优先级高于文件设置

        // get consumer's global configuration
        checkDefault();
        // 与上面修改consumer一样，通过启动参数/系统设置/文件配置的方式修改代码/xml中的配置。
        appendProperties(this);
        // 泛化调用设置，如果是泛化调用则接口类为GeneicService，否则为配置的interfaceName
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }
        if (ProtocolUtils.isGeneric(getGeneric())) {
            interfaceClass = GenericService.class;//interfaceClass=GenericService
        } else {
            try {
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 1、检查是否是接口，2、如果有methods配置，检查methods中声明的方法在接口中是否存在
            checkInterfaceAndMethods(interfaceClass, methods);
        }
        // 用户可以通过系统属性的方式来指定interfaceName对应的url(从系统变量中获取interfaceClass)
        String resolve = System.getProperty(interfaceName);
        String resolveFile = null;
        if (resolve == null || resolve.length() == 0) {
            resolveFile = System.getProperty("dubbo.resolve.file");
            if (resolveFile == null || resolveFile.length() == 0) {
                File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
                if (userResolveFile.exists()) {
                    resolveFile = userResolveFile.getAbsolutePath();
                }
            }
            if (resolveFile != null && resolveFile.length() > 0) {
                Properties properties = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(new File(resolveFile));
                    properties.load(fis);
                } catch (IOException e) {
                    throw new IllegalStateException("Unload " + resolveFile + ", cause: " + e.getMessage(), e);
                } finally {
                    try {
                        if (null != fis) {
                            fis.close();
                        }
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
                resolve = properties.getProperty(interfaceName);//从配置文件中获取interfaceClass
            }
        }
        if (resolve != null && resolve.length() > 0) {
            url = resolve;//对等调用的url赋值为interfaceClass的全路径名
            if (logger.isWarnEnabled()) {
                if (resolveFile != null) {
                    logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
                } else {
                    logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
                }
            }
        }
        if (consumer != null) {
            if (application == null) {
                application = consumer.getApplication();
            }
            if (module == null) {
                module = consumer.getModule();
            }
            //获取一个注册服务
            if (registries == null) {
                registries = consumer.getRegistries();
            }
            //获取一个监控服务
            if (monitor == null) {
                monitor = consumer.getMonitor();
            }
        }
        if (module != null) {
            //获取一个注册服务
            if (registries == null) {
                registries = module.getRegistries();
            }
            //获取一个监控服务
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            //获取一个监控服务
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        // 检测application配置，同样也会调用appendProperties进行参数的修改
        checkApplication();
        // 检查local/stub/mock三个配置是否正确
        checkStub(interfaceClass);
        checkMock(interfaceClass);
        // 将所有配置加入到map中
        Map<String, String> map = new HashMap<String, String>();
        resolveAsyncInterface(interfaceClass, map);

        map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);//(side,consumer)
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());//(dubbo,2.0.2)
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));//(timestamp,time)
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));//(pid,pidNumber)
        }
        if (!isGeneric()) {
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);//(revision,revisionStr)
            }

            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));//(methods,methodsLists)
            }
        }
        map.put(Constants.INTERFACE_KEY, interfaceName);//(interface,interfaceName)
        // 依次将application/module/consumer/reference的配置放入ma
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, consumer, Constants.DEFAULT_KEY);
        appendParameters(map, this);
        Map<String, Object> attributes = null;
        if (methods != null && !methods.isEmpty()) {
            attributes = new HashMap<String, Object>();
            for (MethodConfig methodConfig : methods) {
                appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                attributes.put(methodConfig.getName(), convertMethodConfig2AyncInfo(methodConfig));
            }
        }

        //注册地址
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);//(register.ip,ipStr)

        ref = createProxy(map);

        ConsumerModel consumerModel = new ConsumerModel(getUniqueServiceName(), ref, interfaceClass.getMethods(), attributes);
        //初始化消费者模型
        ApplicationModel.initConsumerModel(getUniqueServiceName(), consumerModel);
    }

    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        // 使用map创建一个URL对象，注意该URL是dubbo重新实现的URL
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        final boolean isJvmRefer;// 是否是本地服务
        if (isInjvm() == null) {
            //url=interfaceClass=<GenericService.class>
            if (url != null && url.length() > 0) {//指定URL的情况下，不做本地引用 // if a url is specified, don't do local reference
                isJvmRefer = false;
            } else {
                // by default, reference local service if there is
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            isJvmRefer = isInjvm();
        }

        // 如果是本地服务，则url使用本地服务的协议形式
        if (isJvmRefer) {
            URL url = new URL(Constants.LOCAL_PROTOCOL, NetUtils.LOCALHOST, 0, interfaceClass.getName()).addParameters(map);
            invoker = refprotocol.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }
        } else {
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (url.getPath() == null || url.getPath().length() == 0) {
                            url = url.setPath(interfaceName);
                        }
                        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                            urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }
            } else {  // assemble URL from register center's configuration
                // 通过注册中心配置拼装URL, 如果没有注册信息会报错
                //eg:  registry://224.5.6.7:1234/org.apache.dubbo.registry.RegistryService?application=demo-consumer&dubbo=2.0.2&pid=17640&qos.port=33333&registry=multicast&timestamp=1542697439865
                List<URL> us = loadRegistries(false);
                if (us != null && !us.isEmpty()) {
                    for (URL u : us) {
                        URL monitorUrl = loadMonitor(u);
                        if (monitorUrl != null) {
                            map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                        }
                        urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                    }
                }
                if (urls.isEmpty()) {
                    throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                }
            }
            // 此处refprotocol为RegistryProtocol，它的refer方法会生成一个Registry（如ZookeeperRegisty、MulticastRegistry),
            // 生成的Registry通过url中的注册中心地址与注册中心建立连接，并订阅相关信息，最终封装成一个invoker

            if (urls.size() == 1) {
                invoker = refprotocol.refer(interfaceClass, urls.get(0));
            } else {
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;
                for (URL url : urls) {
                    invokers.add(refprotocol.refer(interfaceClass, url));
                    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        registryURL = url; // 用了最后一个registry url // use last registry url
                    }
                }
                if (registryURL != null) { // 有 注册中心协议的URL // registry url is available
                    // use AvailableCluster only when register's cluster is available
                    // 对有注册中心的Cluster 只用 AvailableCluster
                    URL u = registryURL.addParameter(Constants.CLUSTER_KEY, AvailableCluster.NAME);
                    invoker = cluster.join(new StaticDirectory(u, invokers));
                } else { // 不是 注册中心的URL // not a registry url
                    invoker = cluster.join(new StaticDirectory(invokers));
                }
            }
        }
        // 检查check属性，如果check为空或未true，检测与服务提供方是否连接成功，连接失败则报错
        Boolean c = check;
        if (c == null && consumer != null) {
            c = consumer.isCheck();
        }
        if (c == null) {
            c = true; // default true
        }
        if (c && !invoker.isAvailable()) {
            // make it possible for consumer to retry later if provider is temporarily unavailable
            initialized = false;
            throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }
        // create service proxy
        // 调用代理生成工厂创建服务代理
        return (T) proxyFactory.getProxy(invoker);
    }

    private void checkDefault() {
        if (consumer == null) {
            consumer = new ConsumerConfig();
        }
        appendProperties(consumer);
    }

    private void resolveAsyncInterface(Class<?> interfaceClass, Map<String, String> map) {
        AsyncFor annotation = interfaceClass.getAnnotation(AsyncFor.class);
        if (annotation == null) {
            return;
        }
        Class<?> target = annotation.value();
        if (!target.isAssignableFrom(interfaceClass)) {
            return;
        }
        this.asyncInterfaceClass = interfaceClass;
        this.interfaceClass = target;
        setInterface(this.interfaceClass.getName());
        map.put(Constants.INTERFACES, interfaceClass.getName());
    }


    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (isGeneric()
                || (getConsumer() != null && getConsumer().isGeneric())) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    @Deprecated
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        checkName("client", client);
        this.client = client;
    }

    @Parameter(excluded = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        this.consumer = consumer;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        return buf.toString();
    }

}
