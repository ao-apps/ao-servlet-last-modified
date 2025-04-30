/*
 * ao-servlet-last-modified - Automatically adds lastModified URL parameters to ensure latest resources always used.
 * Copyright (C) 2013, 2014, 2016, 2017, 2019, 2020, 2021, 2022, 2023, 2024, 2025  AO Industries, Inc.
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
 * along with ao-servlet-last-modified.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoapps.servlet.lastmodified;

import com.aoapps.lang.Strings;
import com.aoapps.lang.io.ContentType;
import com.aoapps.lang.io.FileUtils;
import com.aoapps.lang.io.IoUtils;
import com.aoapps.net.AnyURI;
import com.aoapps.net.URIEncoder;
import com.aoapps.net.URIParser;
import com.aoapps.net.URIResolver;
import com.aoapps.servlet.ServletContextCache;
import com.aoapps.servlet.attribute.ScopeEE;
import com.aoapps.servlet.http.Canonical;
import com.aoapps.servlet.http.Dispatcher;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Sets the modified time to that of the file itself and all dependencies.
 * Currently only *.css supported.
 *
 * <p>When mapped to handle *.css files, parses the underlying file from the web
 * resources and automatically adds lastModified=#### URL parameters.  This allows
 * the replacement of files to be immediately visible to browsers while still
 * efficiently caching when nothing changed.</p>
 *
 * <p>The current CSS parser is extremely simple and may not catch all URLs.
 * Specifically, it only looks for URLs on a line-by-line basis and does not
 * support backslash (\) escapes. (TODO: Are backslash escapes even part of CSS?)</p>
 *
 * <p>TODO: Handle escapes, which might require a parser more complicated than this regular expression,
 * including escaped quotes in quotes, or escaped parenthesis:
 * <a href="https://developer.mozilla.org/en-US/docs/Web/CSS/url()">https://developer.mozilla.org/en-US/docs/Web/CSS/url()</a>,
 * <a href="https://stackoverflow.com/questions/25613552/how-should-i-escape-image-urls-for-css">https://stackoverflow.com/questions/25613552/how-should-i-escape-image-urls-for-css</a>.</p>
 *
 * <p>All files must be in the {@link StandardCharsets#UTF_8} encoding.</p>
 *
 * <p>TODO: Add support for non-url imports
 * TODO: Review recursive import url(...) works correctly (urls within the included urls should be applied)</p>
 *
 * <p>TODO: Rewrite all URLs to be app-relative, with contextPath prefixed, much like how other URLs are rewritten.
 * This is so paths will still be relative to the CSS file even when included into a page directly.</p>
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
   *
   * <p>Only used when the <code>cache-control</code> header has not already been
   * set, which will normally already be set to a much more aggressive value by
   * {@link LastModifiedHeaderFilter}.</p>
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
  public static final String LAST_MODIFIED_HEADER_NAME = "X-com-aoapps-servlet-lastmodified-enabled";

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
      if ("true".equalsIgnoreCase(headerS)) {
        header = Boolean.TRUE;
      } else if ("false".equalsIgnoreCase(headerS)) {
        header = Boolean.FALSE;
      } else {
        header = null;
      }
      this.path = path;
    }

    @Override
    public String toString() {
      return "(" + header + ", " + path + ")";
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof HeaderAndPath)) {
        return false;
      }
      HeaderAndPath other = (HeaderAndPath) obj;
      return
          Objects.equals(header, other.header)
              && path.equals(other.path);
    }

    @Override
    public int hashCode() {
      int hash = Objects.hashCode(header);
      hash = hash * 31 + path.hashCode();
      return hash;
    }
  }

  /**
   * Creates the {@link ParsedCssFile} cache during {@linkplain ServletContextListener application start-up}.
   */
  @WebListener("Creates the ParsedCssFile cache during application start-up.")
  public static class ParsedCssFileCache implements ServletContextListener {

    /**
     * The attribute name used to store the cache.
     */
    private static final ScopeEE.Application.Attribute<ConcurrentMap<HeaderAndPath, ParsedCssFile>> APPLICATION_ATTRIBUTE =
        ScopeEE.APPLICATION.attribute(ParsedCssFileCache.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
      getCache(event.getServletContext());
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
      // Do nothing
    }

    private static ConcurrentMap<HeaderAndPath, ParsedCssFile> getCache(ServletContext servletContext) {
      return APPLICATION_ATTRIBUTE.context(servletContext).computeIfAbsent(name -> new ConcurrentHashMap<>());
    }
  }

  private static class ParsedCssFile {

    private static final Pattern URL_PATTERN = Pattern.compile(
        "url\\s*\\(\\s*(\\S+?)\\s*\\)",
        Pattern.CASE_INSENSITIVE
    );

    private static ParsedCssFile parseCssFile(ServletContext servletContext, HeaderAndPath hap) throws FileNotFoundException, IOException, URISyntaxException {
      ConcurrentMap<HeaderAndPath, ParsedCssFile> parsedCssFileCache = ParsedCssFileCache.getCache(servletContext);
      ServletContextCache servletContextCache = ServletContextCache.getInstance(servletContext);
      // Check the cache
      final long lastModified = servletContextCache.getLastModified(hap.path);
      ParsedCssFile parsedCssFile = parsedCssFileCache.get(hap);
      if (
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
          if (resourceIn == null) {
            throw new FileNotFoundException(hap.path);
          }
          try (BufferedReader in = new BufferedReader(new InputStreamReader(resourceIn, CSS_ENCODING))) {
            cssContent = IoUtils.readFully(in);
          }
        }
        // Replace values while capturing URLs
        StringBuilder newContent = new StringBuilder(cssContent.length() << 1);
        Map<HeaderAndPath, Long> referencedPaths = new HashMap<>();
        Matcher matcher = URL_PATTERN.matcher(cssContent);
        int lastEnd = 0;
        while (matcher.find()) {
          int start = matcher.start(1);
          int end = matcher.end(1);
          // Skip quotes at start
          char ch;
          while (
              start < end
                  && (
                  (ch = cssContent.charAt(start)) == '"'
                      || ch == '\''
              )
          ) {
            start++;
          }
          // Skip quotes at end
          while (
              start < end
                  && (
                  (ch = cssContent.charAt(end - 1)) == '"'
                      || ch == '\''
              )
          ) {
            end--;
          }
          newContent.append(cssContent, lastEnd, start);
          AnyURI uri = new AnyURI(cssContent.substring(start, end));
          AnyURI noFragmentUri = uri.setFragment(null);
          if (logger.isLoggable(Level.FINEST)) {
            logger.finest("match .......: " + matcher.group());
            logger.finest("uri .........: " + uri);
            if (uri != noFragmentUri) {
              logger.finest("noFragmentUri: " + noFragmentUri);
            }
          }
          newContent.append(noFragmentUri.toString());
          // Check for header disabling auto last modified
          if (hap.header == null || hap.header) {
            // Get the resource path relative to the CSS file
            String resourcePath = URIResolver.getAbsolutePath(hap.path, noFragmentUri.toString());
            if (resourcePath.startsWith("/")) {
              URI resourcePathURI = new URI(
                  URIEncoder.encodeURI(
                      resourcePath
                  )
              );
              HeaderAndPath resourceHap = new HeaderAndPath(
                  hap.header,
                  resourcePathURI.getPath()
              );
              // TODO: If the resource is *.css, apply recursively (with a Set used to catch duplicates to avoid stack overflow - just don't recurse when already visited)
              long resourceModified = servletContextCache.getLastModified(resourceHap.path);
              if (resourceModified != 0) {
                referencedPaths.put(resourceHap, resourceModified);
                newContent
                    .append(noFragmentUri.hasQuery() ? '&' : '?')
                    .append(LAST_MODIFIED_PARAMETER_NAME)
                    .append('=')
                    .append(encodeLastModified(resourceModified));
              }
            }
          }
          if (uri.hasFragment()) {
            newContent.append('#');
            uri.appendFragment(newContent);
          }
          lastEnd = matcher.end();
          newContent.append(cssContent, end, lastEnd);
        }
        newContent.append(cssContent, lastEnd, cssContent.length());
        parsedCssFile = new ParsedCssFile(
            servletContextCache,
            lastModified,
            newContent.toString().getBytes(CSS_ENCODING),
            referencedPaths
        );
        parsedCssFileCache.put(hap, parsedCssFile);
        return parsedCssFile;
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
    private final Map<HeaderAndPath, Long> referencedPaths;

    /**
     * The most recent last modified of the CSS file itself and all dependencies.
     */
    private final long newestLastModified;

    private ParsedCssFile(
        ServletContextCache servletContextCache,
        long lastModified,
        byte[] rewrittenCssFile,
        Map<HeaderAndPath, Long> referencedPaths
    ) {
      this.lastModified = lastModified;
      this.referencedPaths = referencedPaths;
      this.rewrittenCssFile = rewrittenCssFile;
      long newest = lastModified;
      for (Map.Entry<HeaderAndPath, Long> entry : referencedPaths.entrySet()) {
        long modified = servletContextCache.getLastModified(entry.getKey().path);
        if (modified > newest) {
          newest = modified;
        }
      }
      this.newestLastModified = newest;
    }

    /**
     * Checks if any of the referencedPaths have been modified.
     */
    // TODO: Handle recursive *.css paths (with a Set to avoid infinite recursion)
    private boolean hasModifiedUrl(ServletContextCache servletContextCache) {
      for (Map.Entry<HeaderAndPath, Long> entry : referencedPaths.entrySet()) {
        if (servletContextCache.getLastModified(entry.getKey().path) != entry.getValue()) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Gets a last modified time given a context-relative path starting with a
   * slash (/).
   *
   * <p>Any file ending in ".css" (case-insensitive) will be parsed and will have
   * a modified time that is equal to the greatest of itself or any referenced
   * URL.</p>
   *
   * @return  the modified time or <code>0</code> when unknown.
   */
  public static long getLastModified(ServletContext servletContext, HttpServletRequest request, String path, String extension) {
    HeaderAndPath hap = new HeaderAndPath(request, path);
    if (CSS_EXTENSION.equals(extension)) {
      try {
        // Parse CSS file, finding all dependencies.
        // Don't re-parse when CSS file not changed, but still check
        // dependencies.
        return ParsedCssFile.parseCssFile(servletContext, hap).newestLastModified;
      } catch (IOException | URISyntaxException e) {
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
   * Fetched some from <a href="https://wikipedia.org/wiki/List_of_file_formats">https://wikipedia.org/wiki/List_of_file_formats</a>.
   */
  private static final Set<String> staticExtensions = new HashSet<>(
      // Related to LocaleFilter.java
      // Related to NoSessionFilter.java
      // Related to SessionResponseWrapper.java
      // Is LastModifiedServlet.java
      // Matches ao-mime-mappings/…/web-fragment.xml
      // Related to ContentType.java
      // Related to MimeType.java
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
          "webp",
          // HTML document: Not included since causes duplicate content URLs
          //"htm",
          //"html",
          //"xhtml",
          //"mhtml",
          // PDF document
          "pdf",
          // XML document
          "xml",
          "xsd",
          "rss",
          // Web development
          "less",
          "sass",
          "scss",
          "css.map",
          "js.map"
      )
  );

  /**
   * Adds a last modified time (to the nearest second) to a URL if the resource is directly available
   * as a local resource.  Only applies to relative URLs (./, ../) or URLs that begin with a slash (/).
   *
   * <p>Will not modify when the request has header {@link #LAST_MODIFIED_HEADER_NAME} equal to "false".</p>
   *
   * <p>Will not modify {@linkplain Canonical Canonical URLs}.</p>
   */
  public static String addLastModified(ServletContext servletContext, HttpServletRequest request, String servletPath, String url, AddLastModified when) throws MalformedURLException {
    // Never try to add if when == falsee
    if (when != AddLastModified.FALSE) {
      // Get the context-relative path (resolves relative paths)
      String resourcePath = URIResolver.getAbsolutePath(
          servletPath,
          url
      );
      if (resourcePath.startsWith("/")) {
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
        } catch (URISyntaxException e) {
          MalformedURLException urlErr = new MalformedURLException(e.getMessage());
          urlErr.initCause(e);
          throw urlErr;
        }
        int dotPos = resourcePath.lastIndexOf('.');
        String extension = (dotPos != -1) ? resourcePath.substring(dotPos + 1).toLowerCase(Locale.ROOT) : null;
        final boolean doAdd;
        if (when == AddLastModified.TRUE) {
          // Always try to add
          doAdd = true;
        } else {
          assert when == AddLastModified.AUTO;
          if (
              // No extension
              extension == null
                  // Check for header disabling auto last modified
                  || "false".equalsIgnoreCase(request.getHeader(LAST_MODIFIED_HEADER_NAME))
                  // Will not modify Canonical URLs
                  || Canonical.get()
          ) {
            doAdd = false;
          } else {
            // Conditionally try to add based on file extension
            if (staticExtensions.contains(extension)) {
              doAdd = true;
            } else {
              // Check for double-dot extension
              dotPos = resourcePath.lastIndexOf('.', dotPos - 1);
              if (dotPos != -1) {
                doAdd = staticExtensions.contains(resourcePath.substring(dotPos + 1).toLowerCase(Locale.ROOT));
              } else {
                doAdd = false;
              }
            }
          }
        }
        if (doAdd) {
          long lastModified = getLastModified(servletContext, request, resourcePath, extension);
          if (lastModified != 0) {
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
    String cacheControlParam = Strings.trimNullIfEmpty(config.getInitParameter("cache-control"));
    if (cacheControlParam == null) {
      // Compatibility with old parameter case
      cacheControlParam = Strings.trimNullIfEmpty(config.getInitParameter("Cache-Control"));
    }
    this.cacheControl = (cacheControlParam == null) ? DEFAULT_CACHE_CONTROL : cacheControlParam;
  }

  @Override
  protected long getLastModified(HttpServletRequest request) {
    // Find the underlying file
    long lastModified = getLastModified(
        getServletContext(),
        request,
        Dispatcher.getCurrentPagePath(request)
    );
    return lastModified == 0 ? -1 : lastModified;
  }

  @Override
  @SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    try {
      // Find the underlying file
      HeaderAndPath hap = new HeaderAndPath(
          request,
          Dispatcher.getCurrentPagePath(request)
      );
      String extension = FileUtils.getExtension(hap.path);
      if (CSS_EXTENSION.equalsIgnoreCase(extension)) {
        // Special case for CSS files
        byte[] rewrittenCss = ParsedCssFile.parseCssFile(getServletContext(), hap).rewrittenCssFile;
        response.setContentType(ContentType.CSS);
        response.setCharacterEncoding(CSS_ENCODING.name());
        if (ScopeEE.Request.INCLUDE_SERVLET_PATH.context(request).get() != null) {
          // When included, write as characters for compatibility
          response.getWriter().write(new String(rewrittenCss, CSS_ENCODING));
        } else {
          // Otherwise, write as binary for performance
          response.setContentLength(rewrittenCss.length);
          if (!response.containsHeader("cache-control")) {
            response.setHeader("cache-control", cacheControl);
          }
          response.getOutputStream().write(rewrittenCss);
        }
      } else {
        throw new ServletException("Unsupported file type: " + extension);
      }
    } catch (FileNotFoundException e) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
    } catch (ThreadDeath | ServletException | IOException td) {
      throw td;
    } catch (Throwable t) {
      throw new ServletException(t);
    }
  }
}
