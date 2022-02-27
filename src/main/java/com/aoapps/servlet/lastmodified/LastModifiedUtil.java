/*
 * ao-servlet-last-modified - Automatically adds lastModified URL parameters to ensure latest resources always used.
 * Copyright (C) 2009, 2010, 2011, 2012, 2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

import com.aoapps.net.URIParameters;
import com.aoapps.servlet.http.Dispatcher;
import com.aoapps.servlet.http.HttpServletUtil;
import java.io.IOException;
import java.net.MalformedURLException;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.JspContext;
import javax.servlet.jsp.PageContext;

/**
 * Static utilities for managing last modified parameters in servlet/JSP/taglib environments.
 *
 * @author  AO Industries, Inc.
 *
 * @see HttpServletUtil
 */
public final class LastModifiedUtil {

	/** Make no instances. */
	private LastModifiedUtil() {throw new AssertionError();}

	/**
	 * @see  HttpServletUtil#buildRedirectURL(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.http.HttpServletUtil.UrlModifier, boolean, boolean)
	 */
	public static String buildRedirectURL(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		String servletPath,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return HttpServletUtil.buildRedirectURL(
			request,
			response,
			servletPath,
			href,
			params,
			newHref -> LastModifiedServlet.addLastModified(servletContext, request, servletPath, newHref, addLastModified),
			absolute,
			canonical
		);
	}

	/**
	 * Builds a URL that should be used for a redirect location,
	 * with path resolved relative to the given request.
	 *
	 * @see  Dispatcher#getCurrentPagePath(javax.servlet.http.HttpServletRequest)
	 * @see  #buildRedirectURL(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 * @see  #sendRedirect(int, javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 */
	public static String buildRedirectURL(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return buildRedirectURL(
			servletContext,
			request,
			response,
			Dispatcher.getCurrentPagePath(request),
			href,
			params,
			addLastModified,
			absolute,
			canonical
		);
	}

	/**
	 * @see  #buildRedirectURL(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 * @see  #sendRedirect(int, javax.servlet.jsp.PageContext, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 */
	public static String buildRedirectURL(
		PageContext pageContext,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return buildRedirectURL(
			pageContext.getServletContext(),
			(HttpServletRequest)pageContext.getRequest(),
			(HttpServletResponse)pageContext.getResponse(),
			href,
			params,
			addLastModified,
			absolute,
			canonical
		);
	}

	/**
	 * @see  #buildRedirectURL(javax.servlet.jsp.PageContext, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 * @see  #sendRedirect(int, javax.servlet.jsp.JspContext, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 */
	public static String buildRedirectURL(
		JspContext jspContext,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return buildRedirectURL(
			(PageContext)jspContext,
			href,
			params,
			addLastModified,
			absolute,
			canonical
		);
	}

	/**
	 * @see  HttpServletUtil#buildURL(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.http.HttpServletUtil.UrlModifier, boolean, boolean)
	 */
	public static String buildURL(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		String servletPath,
		String url,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return HttpServletUtil.buildURL(
			request,
			response,
			servletPath,
			url,
			params,
			newUrl -> LastModifiedServlet.addLastModified(servletContext, request, servletPath, newUrl, addLastModified),
			absolute,
			canonical
		);
	}

	/**
	 * Builds a URL with path resolved relative to the given request.
	 *
	 * @see  Dispatcher#getCurrentPagePath(javax.servlet.http.HttpServletRequest)
	 * @see  #buildURL(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 */
	public static String buildURL(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		String url,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return buildURL(
			servletContext,
			request,
			response,
			Dispatcher.getCurrentPagePath(request),
			url,
			params,
			addLastModified,
			absolute,
			canonical
		);
	}

	/**
	 * @see  #buildURL(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 */
	public static String buildURL(
		PageContext pageContext,
		String url,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return buildURL(
			pageContext.getServletContext(),
			(HttpServletRequest)pageContext.getRequest(),
			(HttpServletResponse)pageContext.getResponse(),
			url,
			params,
			addLastModified,
			absolute,
			canonical
		);
	}

	/**
	 * @see  #buildURL(javax.servlet.jsp.PageContext, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 */
	public static String buildURL(
		JspContext jspContext,
		String url,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException {
		return buildURL(
			(PageContext)jspContext,
			url,
			params,
			addLastModified,
			absolute,
			canonical
		);
	}

	/**
	 * @see  #buildRedirectURL(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 * @see  HttpServletUtil#sendRedirect(int, javax.servlet.http.HttpServletResponse, java.lang.String)
	 *
	 * @throws  IllegalStateException  when the response is already {@linkplain HttpServletResponse#isCommitted() committed}
	 */
	public static void sendRedirect(
		int status,
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		String servletPath,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException, IllegalStateException, IOException {
		HttpServletUtil.sendRedirect(
			status,
			response,
			buildRedirectURL(
				servletContext,
				request,
				response,
				servletPath,
				href,
				params,
				addLastModified,
				absolute,
				canonical
			)
		);
	}

	/**
	 * @see  #buildRedirectURL(javax.servlet.ServletContext, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 * @see  HttpServletUtil#sendRedirect(int, javax.servlet.http.HttpServletResponse, java.lang.String)
	 *
	 * @throws  IllegalStateException  when the response is already {@linkplain HttpServletResponse#isCommitted() committed}
	 */
	public static void sendRedirect(
		int status,
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException, IllegalStateException, IOException {
		HttpServletUtil.sendRedirect(
			status,
			response,
			buildRedirectURL(
				servletContext,
				request,
				response,
				href,
				params,
				addLastModified,
				absolute,
				canonical
			)
		);
	}

	/**
	 * @see  #buildRedirectURL(javax.servlet.jsp.PageContext, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 * @see  HttpServletUtil#sendRedirect(int, javax.servlet.http.HttpServletResponse, java.lang.String)
	 *
	 * @throws  IllegalStateException  when the response is already {@linkplain HttpServletResponse#isCommitted() committed}
	 */
	public static void sendRedirect(
		int status,
		PageContext pageContext,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException, IllegalStateException, IOException {
		HttpServletUtil.sendRedirect(
			status,
			(HttpServletResponse)pageContext.getResponse(),
			buildRedirectURL(
				pageContext,
				href,
				params,
				addLastModified,
				absolute,
				canonical
			)
		);
	}

	/**
	 * @see  #buildRedirectURL(javax.servlet.jsp.JspContext, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 * @see  #sendRedirect(int, javax.servlet.jsp.PageContext, java.lang.String, com.aoapps.net.URIParameters, com.aoapps.servlet.lastmodified.AddLastModified, boolean, boolean)
	 *
	 * @throws  IllegalStateException  when the response is already {@linkplain HttpServletResponse#isCommitted() committed}
	 */
	public static void sendRedirect(
		int status,
		JspContext jspContext,
		String href,
		URIParameters params,
		AddLastModified addLastModified,
		boolean absolute,
		boolean canonical
	) throws MalformedURLException, IllegalStateException, IOException {
		sendRedirect(
			status,
			(PageContext)jspContext,
			href,
			params,
			addLastModified,
			absolute,
			canonical
		);
	}
}
