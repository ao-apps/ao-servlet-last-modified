/*
 * ao-servlet-last-modified - Automatically adds lastModified URL parameters to ensure latest resources always used.
 * Copyright (C) 2013, 2014, 2016, 2017, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-servlet-last-modified.
 *
 * ao-servlet-last-modified is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-servlet-last-modified is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-servlet-last-modified.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.servlet.lastmodified;

import com.aoindustries.io.ContentType;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.IoUtils;
import com.aoindustries.net.AnyURI;
import com.aoindustries.net.URIEncoder;
import com.aoindustries.net.URIParser;
import com.aoindustries.net.URIResolver;
import com.aoindustries.servlet.ServletContextCache;
import com.aoindustries.servlet.http.Canonical;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Sets the modified time to that of the file itself and all dependencies.
 * Currently only *.css supported.
 * <p>
 * When mapped to handle *.css files, parses the underlying file from the web
 * resources and automatically adds lastModified=#### URL parameters.  This allows
 * the replacement of files to be immediately visible to browsers while still
 * efficiently caching when nothing changed.
 * </p>
 * <p>
 * The current CSS parser is extremely simple and may not catch all URLs.
 * Specifically, it only looks for URLs on a line-by-line basis and does not
 * support backslash (\) escapes. (TODO: Are backslash escapes even part of CSS?)
 * </p>
 * <p>
 * TODO: Handle escapes, which might require a parser more complicated than this regular expression,
 * including escaped quotes in quotes, or escaped parenthesis:
 * <a href="https://developer.mozilla.org/en-US/docs/Web/CSS/url()">https://developer.mozilla.org/en-US/docs/Web/CSS/url()</a>,
 * <a href="https://stackoverflow.com/questions/25613552/how-should-i-escape-image-urls-for-css">https://stackoverflow.com/questions/25613552/how-should-i-escape-image-urls-for-css</a>.
 * </p>
 * <p>
 * All files must be in the {@link StandardCharsets#UTF_8} encoding.
 * </p>
 *
 * @see  ServletContextCache  This requires the cache be active
 */
public class LastModifiedServlet extends HttpServlet {

	private static final Logger logger = Logger.getLogger(LastModifiedServlet.class.getName());

	private static final long serialVersionUID = 1L;

	/**
	 * Encoding used on reading files and writing output.
	 */
	private static final Charset CSS_ENCODING = StandardCharsets.UTF_8;

	/**
	 * The extension that will be parsed as CSS file.
	 */
	private static final String CSS_EXTENSION = "css";

	/**
	 * The default, short-term, <code>cache-control</code> header value.
	 * <p>
	 * Only used when the <code>cache-control</code> header has not already been
	 * set, which will normally already be set to a much more aggressive value by
	 * {@link LastModifiedCacheControlFilter}.
	 * </p>
	 */
	// In order documented at https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control
	public static final String DEFAULT_CACHE_CONTROL =
		// Cacheability
		"public"
		// Expiration (5 minutes)
		+ ",max-age=300"
		//+ ",s-maxage=300" // Use same value for proxies
		+ ",max-stale=300"
		+ ",stale-while-revalidate=300"
		+ ",stale-if-error=300";

	/**
	 * The name of the last modified parameter that is optionally added.
	 * The value is URL-safe and does not need to be encoded.
	 */
	public static final String LAST_MODIFIED_PARAMETER_NAME = "lastModified";
	static {
		assert LAST_MODIFIED_PARAMETER_NAME.equals(URIEncoder.encodeURIComponent(LAST_MODIFIED_PARAMETER_NAME)) : "The value is URL-safe and does not need to be encoded: " + LAST_MODIFIED_PARAMETER_NAME;
	}

	/**
	 * The header that may be used to disable automatic lastModified parameters.
	 */
	public static final String LAST_MODIFIED_HEADER_NAME = "X-com-aoindustries-servlet-lastmodified-enabled";

	/**
	 * Encodes a last modified value.
	 * The value is URL-safe and does not need to be encoded.
	 */
	public static String encodeLastModified(long lastModified) {
		String str = Long.toString(lastModified / 1000, 32);
		assert str.equals(URIEncoder.encodeURIComponent(str)) : "The value is URL-safe and does not need to be encoded: " + str;
		return str;
	}

	private static class HeaderAndPath {

		private final Boolean header;
		private final String path;

		private HeaderAndPath(Boolean header, String path) {
			this.header = header;
			this.path = path;
		}

		private HeaderAndPath(HttpServletRequest request, String path) {
			String headerS = request.getHeader(LAST_MODIFIED_HEADER_NAME);
			if("true".equalsIgnoreCase(headerS)) header = Boolean.TRUE;
			else if("false".equalsIgnoreCase(headerS)) header = Boolean.FALSE;
			else header = null;
			this.path = path;
		}

		@Override
		public String toString() {
			return "(" + header + ", " + path + ")";
		}

		@Override
		public boolean equals(Object obj) {
			if(!(obj instanceof HeaderAndPath)) return false;
			HeaderAndPath other = (HeaderAndPath)obj;
			return
				Objects.equals(header, other.header)
				&& path.equals(other.path)
			;
		}

		@Override
		public int hashCode() {
			int hash = Objects.hashCode(header);
			hash = hash * 31 + path.hashCode();
			return hash;
		}
	}

	private static class ParsedCssFile {

		/**
		 * The attribute name used to store the cache.
		 */
		private static final String PARSE_CSS_FILE_CACHE_ATTRIBUTE_NAME = ParsedCssFile.class.getName()+".parseCssFile.cache";

		private static final Pattern URL_PATTERN = Pattern.compile(
			"url\\s*\\(\\s*['\"]?(\\S+)['\"]?\\s*\\)",
			Pattern.CASE_INSENSITIVE
		);

		private static ParsedCssFile parseCssFile(ServletContext servletContext, HeaderAndPath hap) throws FileNotFoundException, IOException, URISyntaxException {
			// Get the cache
			@SuppressWarnings("unchecked")
			Map<HeaderAndPath,ParsedCssFile> cache = (Map<HeaderAndPath,ParsedCssFile>)servletContext.getAttribute(PARSE_CSS_FILE_CACHE_ATTRIBUTE_NAME);
			if(cache == null) {
				// Create new cache
				cache = new HashMap<>();
				servletContext.setAttribute(PARSE_CSS_FILE_CACHE_ATTRIBUTE_NAME, cache);
			}
			ServletContextCache servletContextCache = ServletContextCache.getCache(servletContext);
			synchronized(cache) {
				// Check the cache
				final long lastModified = servletContextCache.getLastModified(hap.path);
				ParsedCssFile parsedCssFile = cache.get(hap);
				if(
					parsedCssFile != null
					&& parsedCssFile.lastModified == lastModified
					&& !parsedCssFile.hasModifiedUrl(servletContextCache)
				) {
					return parsedCssFile;
				} else {
					// (Re)parse the file
					String cssContent;
					{
						InputStream resourceIn = servletContext.getResourceAsStream(hap.path);
						if(resourceIn==null) throw new FileNotFoundException(hap.path);
						try (BufferedReader in = new BufferedReader(new InputStreamReader(resourceIn, CSS_ENCODING))) {
							cssContent = IoUtils.readFully(in);
						}
					}
					// Replace values while capturing URLs
					StringBuilder newContent = new StringBuilder(cssContent.length() << 1);
					Map<HeaderAndPath,Long> referencedPaths = new HashMap<>();
					Matcher matcher = URL_PATTERN.matcher(cssContent);
					int lastEnd = 0;
					while(matcher.find()) {
						int start = matcher.start(1);
						int end = matcher.end(1);
						if(start!=lastEnd) newContent.append(cssContent, lastEnd, start);
						AnyURI uri = new AnyURI(matcher.group(1));
						// The regular expression leaves ' or " at end of URL, strip here:
						//String addAfterUrl = null;
						//if(url.endsWith("'")) {
						//	System.err.println("url ends ': " + url);
						//	url = url.substring(0, url.length()-1);
						//	addAfterUrl = "'";
						//} else if(url.endsWith("\"")) {
						//	System.err.println("url ends \": " + url);
						//	url = url.substring(0, url.length()-1);
						//	addAfterUrl = "\"";
						//}
						//System.err.println("url=" + url);
						AnyURI noFragmentUri = uri.setFragment(null);
						newContent.append(noFragmentUri.toString());
						// Check for header disabling auto last modified
						if(hap.header==null || hap.header) {
							// Get the resource path relative to the CSS file
							String resourcePath = URIResolver.getAbsolutePath(hap.path, noFragmentUri.toString());
							if(resourcePath.startsWith("/")) {
								URI resourcePathURI = new URI(
									URIEncoder.encodeURI(
										resourcePath
									)
								);
								HeaderAndPath resourceHap = new HeaderAndPath(
									hap.header,
									resourcePathURI.getPath()
								);
								long resourceModified = servletContextCache.getLastModified(resourceHap.path);
								if(resourceModified != 0) {
									referencedPaths.put(resourceHap, resourceModified);
									newContent
										.append(noFragmentUri.hasQuery() ? '&' : '?')
										.append(LAST_MODIFIED_PARAMETER_NAME)
										.append('=')
										.append(encodeLastModified(resourceModified))
									;
								}
							}
						}
						if(uri.hasFragment()) {
							newContent.append('#');
							uri.appendFragment(newContent);
						}
						lastEnd = end;
					}
					if(lastEnd < cssContent.length()) newContent.append(cssContent, lastEnd, cssContent.length());
					parsedCssFile = new ParsedCssFile(
						servletContextCache,
						lastModified,
						newContent.toString().getBytes(CSS_ENCODING),
						referencedPaths
					);
					cache.put(hap, parsedCssFile);
					return parsedCssFile;
				}
			}
		}

		/**
		 * The last modified time of the file that was parsed.
		 */
		private final long lastModified;

		/**
		 * The CSS file with all URLs modified.
		 */
		private final byte[] rewrittenCssFile;

		/**
		 * The list of paths that need to be checked to get the new modified time.
		 */
		private final Map<HeaderAndPath,Long> referencedPaths;

		/**
		 * The most recent last modified of the CSS file itself and all dependencies.
		 */
		private final long newestLastModified;

		private ParsedCssFile(
			ServletContextCache servletContextCache,
			long lastModified,
			byte[] rewrittenCssFile,
			Map<HeaderAndPath,Long> referencedPaths
		) {
			this.lastModified = lastModified;
			this.referencedPaths = referencedPaths;
			this.rewrittenCssFile = rewrittenCssFile;
			long newest = lastModified;
			for(Map.Entry<HeaderAndPath,Long> entry : referencedPaths.entrySet()) {
				long modified = servletContextCache.getLastModified(entry.getKey().path);
				if(modified > newest) newest = modified;
			}
			this.newestLastModified = newest;
		}

		/**
		 * Checks if any of the referencedPaths have been modified.
		 */
		private boolean hasModifiedUrl(ServletContextCache servletContextCache) {
			for(Map.Entry<HeaderAndPath,Long> entry : referencedPaths.entrySet()) {
				if(servletContextCache.getLastModified(entry.getKey().path) != entry.getValue()) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * <p>
	 * Gets a last modified time given a context-relative path starting with a
	 * slash (/).
	 * </p>
	 * <p>
	 * Any file ending in ".css" (case-insensitive) will be parsed and will have
	 * a modified time that is equal to the greatest of itself or any referenced
	 * URL.
	 * </p>
	 *
	 * @return  the modified time or <code>0</code> when unknown.
	 */
	public static long getLastModified(ServletContext servletContext, HttpServletRequest request, String path, String extension) {
		HeaderAndPath hap = new HeaderAndPath(request, path);
		if(CSS_EXTENSION.equals(extension)) {
			try {
				// Parse CSS file, finding all dependencies.
				// Don't re-parse when CSS file not changed, but still check
				// dependencies.
				return ParsedCssFile.parseCssFile(servletContext, hap).newestLastModified;
			} catch(IOException | URISyntaxException e) {
				return 0;
			}
		} else {
			return ServletContextCache.getLastModified(servletContext, hap.path);
		}
	}

	/**
	 * Automatically determines extension from path.
	 * 
	 * @see  #getLastModified(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
	 */
	public static long getLastModified(ServletContext servletContext, HttpServletRequest request, String path) {
		return getLastModified(
			servletContext,
			request,
			path,
			FileUtils.getExtension(path)
		);
	}

	/**
	 * Fetched some from <a href="https://wikipedia.org/wiki/List_of_file_formats">https://wikipedia.org/wiki/List_of_file_formats</a>
	 */
	private static final Set<String> staticExtensions = new HashSet<>(
		// Note: This list is very similar to ao-servlet-mime-mappings/.../web-fragment.xml
		// TODO: Move mime-mappings to the web-fragment.xml in this project?
		// TODO: Split into ao-servlet-mime-mappings project for clarity?
		Arrays.asList(
			// CSS
			"css",
			// Diagrams
			"dia",
			// Java
			"jar",
			"class",
			"jnlp",
			"tld",
			// JavaScript
			"js",
			"spt",
			"jsfl",
			// Image types
			"bmp",
			"exif",
			"gif",
			"ico",
			"jfif",
			"jpg",
			"jpeg",
			"jpe",
			"mng",
			"nitf",
			"png",
			"svg",
			"tif",
			"tiff",
			// HTML document
			"htm",
			"html",
			"xhtml",
			"mhtml",
			// PDF document
			"pdf",
			// XML document
			"xml",
			"xsd",
			"rss"
		)
	);

	/**
	 * <p>
	 * Adds a last modified time (to the nearest second) to a URL if the resource is directly available
	 * as a local resource.  Only applies to relative URLs (./, ../) or URLs that begin with a slash (/).
	 * </p>
	 * <p>
	 * Will not modify when the request has header {@link #LAST_MODIFIED_HEADER_NAME} equal to "false".
	 * </p>
	 * <p>
	 * Will not modify {@linkplain Canonical Canonical URLs}.
	 * </p>
	 */
	public static String addLastModified(ServletContext servletContext, HttpServletRequest request, String servletPath, String url, AddLastModified when) throws MalformedURLException {
		// Never try to add if when==falsee
		if(when != AddLastModified.FALSE) {
			// Get the context-relative path (resolves relative paths)
			String resourcePath = URIResolver.getAbsolutePath(
				servletPath,
				url
			);
			if(resourcePath.startsWith("/")) {
				// Strip parameters and anchor from resourcePath
				try {
					resourcePath = new URI(
						URIEncoder.encodeURI(
							resourcePath.substring(
								0,
								URIParser.getPathEnd(resourcePath)
							)
						)
					).getPath();
				} catch(URISyntaxException e) {
					MalformedURLException urlErr = new MalformedURLException(e.getMessage());
					urlErr.initCause(e);
					throw urlErr;
				}
				String extension = FileUtils.getExtension(resourcePath).toLowerCase(Locale.ROOT);
				final boolean doAdd;
				if(when == AddLastModified.TRUE) {
					// Always try to add
					doAdd = true;
				} else {
					assert when == AddLastModified.AUTO;
					if(
						// Check for header disabling auto last modified
						"false".equalsIgnoreCase(request.getHeader(LAST_MODIFIED_HEADER_NAME))
						// Will not modify Canonical URLs
						|| Canonical.get()
					) {
						doAdd = false;
					} else {
						// Conditionally try to add based on file extension
						doAdd = staticExtensions.contains(
							extension
						);
					}
				}
				if(doAdd) {
					long lastModified = getLastModified(servletContext, request, resourcePath, extension);
					if(lastModified != 0) {
						url = new AnyURI(url)
							.addEncodedParameter(LAST_MODIFIED_PARAMETER_NAME, encodeLastModified(lastModified))
							.toString();
					}
				}
			}
		}
		return url;
	}

	private String cacheControl;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		String cacheControlParam = StringUtility.trimNullIfEmpty(config.getInitParameter("cache-control"));
		if(cacheControlParam == null) {
			// Compatibility with old parameter case
			cacheControlParam = StringUtility.trimNullIfEmpty(config.getInitParameter("Cache-Control"));
		}
		this.cacheControl = (cacheControlParam == null) ? DEFAULT_CACHE_CONTROL : cacheControlParam;
	}

	@Override
	protected long getLastModified(HttpServletRequest request) {
		// Find the underlying file
		long lastModified = getLastModified(getServletContext(), request, request.getServletPath());
		return lastModified==0 ? -1 : lastModified;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			// Find the underlying file
			HeaderAndPath hap = new HeaderAndPath(request, request.getServletPath());
			String extension = FileUtils.getExtension(hap.path);
			if(CSS_EXTENSION.equalsIgnoreCase(extension)) {
				// Special case for CSS files
				byte[] rewrittenCss = ParsedCssFile.parseCssFile(getServletContext(), hap).rewrittenCssFile;
				response.setContentType(ContentType.CSS);
				response.setCharacterEncoding(CSS_ENCODING.name());
				response.setContentLength(rewrittenCss.length);
				if(!response.containsHeader("cache-control")) {
					response.setHeader("cache-control", cacheControl);
				}
				OutputStream out = response.getOutputStream();
				out.write(rewrittenCss);
			} else {
				throw new ServletException("Unsupported file type: " + extension);
			}
		} catch(FileNotFoundException e) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
		} catch(RuntimeException | IOException | URISyntaxException e) {
			logger.log(Level.SEVERE, null, e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}
}
