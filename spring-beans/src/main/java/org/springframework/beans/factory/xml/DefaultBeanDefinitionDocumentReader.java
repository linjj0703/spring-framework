/*
 * Copyright 2002-2018 the original author or authors.
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
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface that
 * reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements, and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). {@code <beans>} does not need to be the root
 * element of the XML document: this class will parse all bean definition elements
 * in the XML file, regardless of the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	public static final String NESTED_BEANS_ELEMENT = "beans";

	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";

	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";

	public static final String PROFILE_ATTRIBUTE = "profile";


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private XmlReaderContext readerContext;

	@Nullable
	private BeanDefinitionParserDelegate delegate;


	/**
	 * This implementation parses bean definitions according to the "spring-beans" XSD
	 * (or DTD, historically).
	 * <p>Opens a DOM Document; then initializes the default settings
	 * specified at the {@code <beans/>} level; then parses the contained bean definitions.
	 */
	@Override
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		this.readerContext = readerContext;
		//注册BeanDefinitions
		doRegisterBeanDefinitions(doc.getDocumentElement());
	}

	/**
	 * Return the descriptor for the XML resource that this parser works on.
	 */
	protected final XmlReaderContext getReaderContext() {
		Assert.state(this.readerContext != null, "No XmlReaderContext available");
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * to pull the source metadata from the supplied {@link Element}.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return getReaderContext().extractSource(ele);
	}


	/**
	 * Register each bean definition within the given root {@code <beans/>} element.
	 */
	@SuppressWarnings("deprecation")  // for Environment.acceptsProfiles(String...)
	protected void doRegisterBeanDefinitions(Element root) {
		// Any nested <beans> elements will cause recursion in this method. In
		// order to propagate and preserve <beans> default-* attributes correctly,
		// keep track of the current (parent) delegate, which may be null. Create
		// the new (child) delegate with a reference to the parent for fallback purposes,
		// then ultimately reset this.delegate back to its original (parent) reference.
		// this behavior emulates a stack of delegates without actually necessitating one.
		BeanDefinitionParserDelegate parent = this.delegate;
		this.delegate = createDelegate(getReaderContext(), root, parent);

		if (this.delegate.isDefaultNamespace(root)) {
			String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
			if (StringUtils.hasText(profileSpec)) {
				String[] specifiedProfiles = StringUtils.tokenizeToStringArray(
						profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
				// We cannot use Profiles.of(...) since profile expressions are not supported
				// in XML config. See SPR-12458 for details.
				if (!getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
					if (logger.isDebugEnabled()) {
						logger.debug("Skipped XML bean definition file due to specified profiles [" + profileSpec +
								"] not matching: " + getReaderContext().getResource());
					}
					return;
				}
			}
		}

		preProcessXml(root);
		//解析注册BeanDefinitions
		parseBeanDefinitions(root, this.delegate);
		postProcessXml(root);

		this.delegate = parent;
	}

	protected BeanDefinitionParserDelegate createDelegate(
			XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {

		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		delegate.initDefaults(root, parentDelegate);
		return delegate;
	}

	/**解析注册BeanDefinitions
	 * Parse the elements at the root level in the document:
	 * "import", "alias", "bean".
	 * @param root the DOM root element of the document
	 */
	protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
		if (delegate.isDefaultNamespace(root)) {
			NodeList nl = root.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				if (node instanceof Element) {
					Element ele = (Element) node;
					if (delegate.isDefaultNamespace(ele)) {
						//解析注册BeanDefinitions
						parseDefaultElement(ele, delegate);
					}
					else {
						delegate.parseCustomElement(ele);
					}
				}
			}
		}
		else {
			delegate.parseCustomElement(root);
		}
	}

	/**
	 * 解析文档对象,解析xml文档对象的各个标签 : <import> <alias> <bean> <beans>
	 * @param ele
	 * @param delegate
	 */
	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		// import  导入其他配置文件信息
		if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
			importBeanDefinitionResource(ele);
		}
		// alias
		else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
			processAliasRegistration(ele);
		}
		//bean : 最重要也是最复杂的bean标签解析
		else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
			//单个<bean>标签对象的解析
			processBeanDefinition(ele, delegate);
		}
		//beans
		else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
			// recurse
			//<beans>标签对象的解析,相当于递归调用
			doRegisterBeanDefinitions(ele);
		}
	}

	/**解析import标签元素
	 *
	 * spring.xml 配置文件中，使用 import 标签的方式导入其他模块的配置文件。
	 *
	 * 如果有配置需要修改直接修改相应配置文件即可。
	 * 若有新的模块需要引入直接增加 import 即可。
	 * 这样大大简化了配置后期维护的复杂度，同时也易于管理。
	 *
	 * Parse an "import" element and load the bean definitions
	 * from the given resource into the bean factory.
	 */
	protected void importBeanDefinitionResource(Element ele) {
		// <1> 获取 resource 的属性值
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		if (!StringUtils.hasText(location)) {
			// 为空，直接退出
			getReaderContext().error("Resource location must not be empty", ele);
			return;
		}
		// <2> 解析系统属性，格式如 ："${user.dir}"
		// Resolve system properties: e.g. "${user.dir}"
		location = getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);

		// 实际 Resource 集合，即 import 的地址，有哪些 Resource 资源
		Set<Resource> actualResources = new LinkedHashSet<>(4);

		// <3> 判断 location 是相对路径还是绝对路径
		// Discover whether the location is an absolute or relative URI
		boolean absoluteLocation = false;
		try {
			/**
			 * 判断绝对路径的规则如下：
			 *
			 * <1> 以 classpath*: 或者 classpath: 开头的为绝对路径。能够通过该 location 构建出 java.net.URL 为绝对路径。
			 *
			 * <2> 根据 location 构造 java.net.URI 判断调用 #isAbsolute() 方法，判断是否为绝对路径。
			 *
			 */
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		}
		catch (URISyntaxException ex) {
			// cannot convert to an URI, considering the location relative
			// unless it is the well-known Spring prefix "classpath*:"
		}

		// Absolute or relative?
		/**
		 *
		 * 处理绝对路径方法整个逻辑(#getReaderContext().getReader().loadBeanDefinitions())：
		 *
		 * 首先，获取 ResourceLoader 对象。
		 * 然后，根据不同的 ResourceLoader 执行不同的逻辑，主要是可能存在多个 Resource 。
		 * 最终，都会回归到 XmlBeanDefinitionReader#loadBeanDefinitions(Resource... resources) 方法，所以这是一个递归的过程。
		 * 另外，获得到的 Resource 的对象或数组，都会添加到 actualResources 中。
		 */
		// <4> 绝对路径
		if (absoluteLocation) {
			try {
				// 添加配置文件地址的 Resource 到 actualResources 中，并加载相应的 BeanDefinition们
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		}
		/**
		 *
		 * 处理相对路径
		 * 如果 location 是相对路径，则会根据相应的 Resource 计算出相应的相对路径的 Resource 对象 ，然后：
		 *
		 * 若该 Resource 存在，则调用 XmlBeanDefinitionReader#loadBeanDefinitions() 方法，进行 BeanDefinition 加载。
		 * 否则，构造一个绝对 location( 即 StringUtils.applyRelativePath(baseLocation, location) 处的代码)，
		 * 并调用 #loadBeanDefinitions(String location, Set<Resource> actualResources) 方法，与绝对路径过程一样。
		 *
		 */
		// <5> 相对路径
		else {
			// No URL -> considering resource location as relative to the current file.
			try {
				int importCount;
				// 创建相对地址的 Resource
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				// 存在
				if (relativeResource.exists()) {
					// 加载 relativeResource 中的 BeanDefinition 们
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					// 添加到 actualResources 中
					actualResources.add(relativeResource);
				}
				// 不存在
				else {
					// 获得根路径地址
					String baseLocation = getReaderContext().getResource().getURL().toString();
					// 添加配置文件地址的 Resource 到 actualResources 中，并加载相应的 BeanDefinition 们
					importCount = getReaderContext().getReader().loadBeanDefinitions(
							StringUtils.applyRelativePath(baseLocation, location)/* 计算绝对路径 */, actualResources);
				}
				if (logger.isTraceEnabled()) {
					logger.trace("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			}
			catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error(
						"Failed to import bean definitions from relative location [" + location + "]", ele, ex);
			}
		}
		// <6> 解析成功后，进行监听器激活处理
		Resource[] actResArray = actualResources.toArray(new Resource[0]);
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));
	}

	/**
	 * Process the given alias element, registering the alias with the registry.
	 */
	protected void processAliasRegistration(Element ele) {
		String name = ele.getAttribute(NAME_ATTRIBUTE);
		String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
		boolean valid = true;
		if (!StringUtils.hasText(name)) {
			getReaderContext().error("Name must not be empty", ele);
			valid = false;
		}
		if (!StringUtils.hasText(alias)) {
			getReaderContext().error("Alias must not be empty", ele);
			valid = false;
		}
		if (valid) {
			try {
				getReaderContext().getRegistry().registerAlias(name, alias);
			}
			catch (Exception ex) {
				getReaderContext().error("Failed to register alias '" + alias +
						"' for bean with name '" + name + "'", ele, ex);
			}
			getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
		}
	}

	/**解析bean标签
	 *
	 * 整个过程分为四个步骤：
	 *
	 * 1.调用 BeanDefinitionParserDelegate#parseBeanDefinitionElement(Element ele, BeanDefinitionParserDelegate delegate) 方法，进行元素解析。
	 * 		如果解析失败，则返回 null，错误由 ProblemReporter 处理。
	 * 		如果解析成功，则返回 BeanDefinitionHolder 实例 bdHolder 。BeanDefinitionHolder 为持有 name 和 alias 的 BeanDefinition。
	 * 		详细解析，见 「2. parseBeanDefinitionElement」 。
	 * 2.若实例 bdHolder 不为空，则调用 BeanDefinitionParserDelegate#decorateBeanDefinitionIfRequired(Element ele, BeanDefinitionHolder bdHolder) 方法，
	 * 进行自定义标签处理。
	 * 		解析完成后，则调用 BeanDefinitionReaderUtils#registerBeanDefinition(BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry) 方法，
	 * 		对 bdHolder 进行 BeanDefinition 的注册。
	 * 3.发出响应事件，通知相关的监听器，完成 Bean 标签解析。
	 *
	 * Process the given bean element, parsing the bean definition
	 * and registering it with the registry.
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
		// 进行 bean 元素解析。
		// <1> 如果解析成功，则返回 BeanDefinitionHolder 对象。而 BeanDefinitionHolder 为 name 和 alias 的 BeanDefinition 对象
		// 如果解析失败，则返回 null 。
		BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
		/**
		 * 如果解析成功（返回的 bdHolder != null ），
		 * 则调用 BeanDefinitionParserDelegate#decorateBeanDefinitionIfRequired(Element ele, BeanDefinitionHolder definitionHolder) 方法，
		 * 完成自定义标签元素的解析。
		 */
		if (bdHolder != null) {
			/**
			 * 1. 使用自定义标签
			 * 扩展 Spring 自定义标签配置一般需要以下几个步骤：
			 *
			 * 1.创建一个需要扩展的组件。
			 * 2.定义一个 XSD 文件，用于描述组件内容。
			 * 3.创建一个实现
			 *  	@see org.springframework.beans.factory.xml.AbstractSingleBeanDefinitionParser 接口的类，用来解析 XSD 文件中的定义和组件定义。
			 * 4.创建一个 Handler，继承
			 * 		@see org.springframework.beans.factory.xml.NamespaceHandlerSupport 抽象类 ，用于将组件注册到 Spring 容器。
			 * 5.编写 spring.handlers 和 Spring.schemas 文件。
			 *
			 */
			// <2> 进行自定义标签处理
			bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
			try {
				// <3> 进行 BeanDefinition 的注册
				// Register the final decorated instance.
				BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to register bean definition with name '" +
						bdHolder.getBeanName() + "'", ele, ex);
			}
			// <4> 发出响应事件，通知相关的监听器，已完成该 Bean 标签的解析。
			// Send registration event.
			getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
		}
	}


	/**
	 * Allow the XML to be extensible by processing any custom element types first,
	 * before we start to process the bean definitions. This method is a natural
	 * extension point for any other custom pre-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types last,
	 * after we finished processing the bean definitions. This method is a natural
	 * extension point for any other custom post-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
