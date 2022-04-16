/*
 * ao-servlet-last-modified - Automatically adds lastModified URL parameters to ensure latest resources always used.
 * Copyright (C) 2013, 2014, 2016, 2017, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

import com.aoapps.servlet.http.Canonical;

/**
 * The options for when to add last modified parameters.
 */
public enum AddLastModified {

	/**
	 * Always tries to add last modified time.
	 */
	TRUE("true"),

	/**
	 * Never tries to add last modified time.
	 */
	FALSE("false"),

	/**
	 * Only tries to add last modified time to URLs that are both not
	 * {@link Canonical} and match expected static resource files, by
	 * extension.  This list is for the paths generally used for
	 * distributing web content and may not include every possible static
	 * file type.
	 */
	AUTO("auto");

	public static AddLastModified valueOfLowerName(String lowerName) {
		if(TRUE .lowerName.equals(lowerName)) return TRUE;
		if(FALSE.lowerName.equals(lowerName)) return FALSE;
		if(AUTO .lowerName.equals(lowerName)) return AUTO;
		// No match
		throw new IllegalArgumentException(lowerName);
	}

	private final String lowerName;

	private AddLastModified(String lowerName) {
		this.lowerName = lowerName;
	}

	public String getLowerName() {
		return lowerName;
	}
}
