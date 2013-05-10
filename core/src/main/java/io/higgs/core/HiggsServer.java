/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.higgs.core;

import io.higgs.core.reflect.PackageScanner;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 */
public class HiggsServer<C extends ServerConfig> {

    private final int port;
    private final Set<MethodProcessor> methodProcessors = new HashSet<>();
    private final Queue<ProtocolDetectorFactory> detectors = new ConcurrentLinkedDeque<>();
    protected final Set<ProtocolConfiguration> protocolConfigurations =
            Collections.newSetFromMap(new ConcurrentHashMap<ProtocolConfiguration, Boolean>());

    /**
     * A sorted set of methods. Methods are sorted in descending order of priority.
     */
    private Queue<InvokableMethod> methods = new ConcurrentLinkedDeque<>();
    private Queue<ObjectFactory> factories = new ConcurrentLinkedDeque<>();
    private EventLoopGroup bossGroup = new NioEventLoopGroup();
    private EventLoopGroup workerGroup = new NioEventLoopGroup();
    private ServerBootstrap bootstrap = new ServerBootstrap();
    private Channel channel;
    private boolean detectSsl;
    private boolean detectGzip;
    private final C config;
    private Logger log = LoggerFactory.getLogger(getClass());
    Class<method> methodClass = method.class;
    private boolean onlyRegisterAnnotatedMethods = true;

    public HiggsServer(String configFile, Class<C> klass) {
        this(configFile, klass, null);
    }

    public HiggsServer(String configFile, Class<C> klass, Constructor constructor) {
        if (configFile == null || configFile.isEmpty()) {
            throw new IllegalArgumentException(String.format("usage: %s path/to/config.yml", getClass().getName()));
        }
        Yaml yaml;
        if (constructor != null) {
            yaml = new Yaml(constructor);
        } else {
            yaml = new Yaml();
        }
        Path configPath = Paths.get(configFile).toAbsolutePath();
        try {
            config = yaml.loadAs(new FileInputStream(configPath.toFile()), klass);
        } catch (Throwable e) {
            //start up error. should not continue
            throw new IllegalStateException(String.format("The server cannot be started, unable to load config (%s)",
                    configPath), e);
        }
        this.port = config.port;
    }

    /**
     * Start the server causing it to bind to the provided {@link #port}
     *
     * @throws UnsupportedOperationException if the server's already started
     */
    public void start() {
        if (channel != null) {
            throw new UnsupportedOperationException("Server already started");
        }
        try {
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new Transducer(detectSsl, detectGzip, detectors,
                                    methods));
                        }
                    });
            // Bind and start to accept incoming connections.
            channel = bootstrap.bind(port).sync().channel();
        } catch (Throwable t) {
            log.warn("Error starting server", t);
        }
    }

    public void stop() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    public void setDetectSsl(boolean detectSsl) {
        this.detectSsl = detectSsl;
    }

    public void setDetectGzip(boolean detectGzip) {
        this.detectGzip = detectGzip;
    }

    /**
     * Chose whether ALL methods in a registered class are registered or
     * just the ones that have been explicitly annotated with {@link method}
     *
     * @param v if true only explicitly marked methods are registered
     */
    public void setOnlyRegisterAnnotatedMethods(boolean v) {
        onlyRegisterAnnotatedMethods = v;
    }

    public void registerProtocol(ProtocolConfiguration protocolConfiguration) {
        protocolConfigurations.add(protocolConfiguration);
        protocolConfiguration.initialise(this);
        registerProtocolDetectorFactory(protocolConfiguration.getProtocol());
        registerMethodProcessor(protocolConfiguration.getMethodProcessor());
    }

    public void registerMethodProcessor(MethodProcessor processor) {
        if (processor == null) {
            throw new IllegalArgumentException("Method processor cannot be null");
        }
        methodProcessors.add(processor);
    }

    /**
     * Registers a new protocol for the server to detect and handle
     *
     * @param factory the detector factories
     */
    public void registerProtocolDetectorFactory(ProtocolDetectorFactory factory) {
        detectors.add(factory);
    }

    public void registerPackage(Package p) {
        registerPackage(p.getName());
    }

    public void registerPackage(String name) {
        for (Class<?> c : PackageScanner.get(name)) {
            if (ObjectFactory.class.isAssignableFrom(c)) {
                registerObjectFactory((Class<ObjectFactory>) c);
            } else {
                registerClass(c);
            }
        }
    }

    public void registerObjectFactory(Class<ObjectFactory> c) {
        try {
            ObjectFactory factory = c.
                    getConstructor(HiggsServer.class, Set.class)
                    .newInstance(this, protocolConfigurations);
            registerObjectFactory(factory);
        } catch (InstantiationException | NoSuchMethodException | InvocationTargetException e) {
            log.warn(String.format("Unable to create instance of ObjectFactory %s", c.getName()), e);
        } catch (IllegalAccessException e) {
            log.warn(String.format("Unable to access ObjectFactory %s", c.getName()), e);
        }
    }

    public void registerObjectFactory(ObjectFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("Cannot register a null object factories");
        }
        factories.add(factory);
    }

    public void registerClass(Class<?> c) {
        registerMethods(c);
    }

    /**
     * Register a class's methods
     *
     * @param klass the class to register or null if factories is set
     * @throws IllegalStateException if the class is null
     */
    public void registerMethods(Class<?> klass) {
        if (klass == null) {
            throw new IllegalArgumentException("Attempting to register null class");
        }
        //is the annotation applied to the whole class or not?
        boolean registerAllMethods = !klass.isAnnotationPresent(methodClass);
        Method[] m = klass.getMethods();
        for (Method method : m) {
            if (onlyRegisterAnnotatedMethods && !method.isAnnotationPresent(methodClass)) {
                continue;
            }
            InvokableMethod im = null;
            for (MethodProcessor mp : methodProcessors) {
                im = mp.process(method, klass, factories);
                if (im != null) {
                    break;
                }
            }
            if (im == null) {
                log.warn(String.format("Method not registered. No method processor registered that can handle %s",
                        method.getName()));
                return;
            }
            if (registerAllMethods) {
                boolean hasListener = method.isAnnotationPresent(methodClass);
                //opt out if the annotation is present and optout is set to true
                boolean optout = hasListener && method.getAnnotation(methodClass).optout();
                if (!optout) {
                    //register all methods is true, the method hasn't been opted out
                    methods.add(im);
                    im.registered();
                }
            } else {
                if (method.isAnnotationPresent(methodClass)
                        && !method.getAnnotation(methodClass).optout()) {
                    //if we're not registering all methods, AND this method has the annotation
                    //AND optout is not set to true
                    if (methods.add(im)) {
                        im.registered();
                    } else {
                        throw new UnsupportedOperationException(String.format("Unable to add invokable method \n%s" +
                                "\n for path \n%s", im, im.path().getUri()));
                    }
                }
            }
        }
    }

    public C getConfig() {
        return config;
    }

    /**
     * @return The set of registered object factories
     */
    public Queue<ObjectFactory> getFactories() {
        return factories;
    }
}