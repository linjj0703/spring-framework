/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.xml;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;

/**
 * {@code EntityResolver} implementation that tries to resolve entity references
 * through a {@link org.springframework.core.io.ResourceLoader} (usually,
 * relative to the resource base of an {@code ApplicationContext}), if applicable.
 * Extends {@link DelegatingEntityResolver} to also provide DTD and XSD lookup.
 *
 * <p>Allows to use standard XML entities to include XML snippets into an
 * application context definition, for example to split a large XML file
 * into various modules. The include paths can be relative to the
 * application context's resource base as usual, instead of relative
 * to the JVM working directory (the XML parser's default).
 *
 * <p>Note: In addition to relative paths, every URL that specifies a
 * file in the current system root, i.e. the JVM working directory,
 * will be interpreted relative to the application context too.
 *
 * @author Juergen Hoeller
 * @since 31.07.2003
 * @see org.springframework.core.io.ResourceLoader
 * @see org.springframework.context.ApplicationContext
 *
 * 继承自 DelegatingEntityResolver 类，通过 ResourceLoader 来解析实体的引用。
 * EntityResolver 的作用就是，通过实现它，应用可以自定义如何寻找【验证文件】的逻辑。
 *
 * Spring中对于EntityResolver 的解释:
 * 何为EntityResolver？官网这样解释：如果 SAX 应用程序需要实现自定义处理外部实体，则必须实现此接口并使用 setEntityResolver 方法向SAX 驱动器注册一个实例。
 * 也就是说，对于解析一个XML，SAX 首先读取该 XML 文档上的声明，根据声明去寻找相应的 DTD 定义，以便对文档进行一个验证。默认的寻找规则，
 * 即通过网络（实现上就是声明的DTD的URI地址）来下载相应的DTD声明，并进行认证。下载的过程是一个漫长的过程，而且当网络中断或不可用时，这里会报错，
 * 就是因为相应的DTD声明没有被找到的原因。
 *
 * EntityResolver 的作用是项目本身就可以提供一个如何寻找 DTD 声明的方法，即由程序来实现寻找 DTD 声明的过程，比如我们将 DTD 文件放到项目中某处，
 * 在实现时直接将此文档读取并返回给 SAX 即可。这样就避免了通过网络来寻找相应的声明。
 *
 *
 * @see ResourceEntityResolver 的重点，是在于如何获取【验证文件】，从而验证用户写的 XML 是否通过验证。
 */
public class ResourceEntityResolver extends DelegatingEntityResolver {

	private static final Log logger = LogFactory.getLog(ResourceEntityResolver.class);

	private final ResourceLoader resourceLoader;


	/**
	 * Create a ResourceEntityResolver for the specified ResourceLoader
	 * (usually, an ApplicationContext).
	 * @param resourceLoader the ResourceLoader (or ApplicationContext)
	 * to load XML entity includes with
	 */
	public ResourceEntityResolver(ResourceLoader resourceLoader) {
		super(resourceLoader.getClassLoader());
		this.resourceLoader = resourceLoader;
	}

	/**
	 * 接口方法接收两个参数 publicId 和 systemId ，并返回 InputSource 对象。两个参数声明如下：
	 *
	 * publicId ：被引用的外部实体的公共标识符，如果没有提供，则返回 null 。
	 * systemId ：被引用的外部实体的系统标识符。
	 * @param publicId 被引用的外部实体的公共标识符
	 * @param systemId 被引用的外部实体的系统标识符
	 * @return
	 * @throws SAXException
	 * @throws IOException
	 *
	 *两种不一样的验证模式的格式校验
	 *
	 * DTD 验证模式
	 * publicId：-//SPRING//DTD BEAN 2.0//EN
	 * systemId：http://www.springframework.org/dtd/spring-beans.dtd
	 *
	 *XSD 验证模式
	 * publicId：null
	 * systemId：http://www.springframework.org/schema/beans/spring-beans.xsd
	 *
	 * 方法的逻辑流程:
	 * 	首先，调用父类的方法，进行解析。
	 * 	如果失败，使用 resourceLoader ，尝试读取 systemId 对应的 Resource 资源。
	 *
	 */
	@Override
	@Nullable
	public InputSource resolveEntity(@Nullable String publicId, @Nullable String systemId)
			throws SAXException, IOException {
		// 调用父类的方法，进行解析
		InputSource source = super.resolveEntity(publicId, systemId);
		// 解析失败，resourceLoader 进行解析
		if (source == null && systemId != null) {
			// 获得 resourcePath ，即 Resource 资源地址
			String resourcePath = null;
			try {
				// 使用 UTF-8 ，解码 systemId
				String decodedSystemId = URLDecoder.decode(systemId, "UTF-8");
				// 转换成 URL 字符串
				String givenUrl = new URL(decodedSystemId).toString();
				// 解析文件资源的相对路径（相对于系统根路径）
				String systemRootUrl = new File("").toURI().toURL().toString();
				// Try relative to resource base if currently in system root.
				if (givenUrl.startsWith(systemRootUrl)) {
					resourcePath = givenUrl.substring(systemRootUrl.length());
				}
			}
			catch (Exception ex) {
				// Typically a MalformedURLException or AccessControlException.
				if (logger.isDebugEnabled()) {
					logger.debug("Could not resolve XML entity [" + systemId + "] against system root URL", ex);
				}
				// No URL (or no resolvable URL) -> try relative to resource base.
				resourcePath = systemId;
			}
			if (resourcePath != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Trying to locate XML entity [" + systemId + "] as resource [" + resourcePath + "]");
				}
				// 获得 Resource 资源
				Resource resource = this.resourceLoader.getResource(resourcePath);
				// 创建 InputSource 对象
				source = new InputSource(resource.getInputStream());
				// 设置 publicId 和 systemId 属性
				source.setPublicId(publicId);
				source.setSystemId(systemId);
				if (logger.isDebugEnabled()) {
					logger.debug("Found XML entity [" + systemId + "]: " + resource);
				}
			}
			else if (systemId.endsWith(DTD_SUFFIX) || systemId.endsWith(XSD_SUFFIX)) {
				// External dtd/xsd lookup via https even for canonical http declaration
				String url = systemId;
				if (url.startsWith("http:")) {
					url = "https:" + url.substring(5);
				}
				try {
					source = new InputSource(new URL(url).openStream());
					source.setPublicId(publicId);
					source.setSystemId(systemId);
				}
				catch (IOException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Could not resolve XML entity [" + systemId + "] through URL [" + url + "]", ex);
					}
					// Fall back to the parser's default behavior.
					source = null;
				}
			}
		}

		return source;
	}

}
