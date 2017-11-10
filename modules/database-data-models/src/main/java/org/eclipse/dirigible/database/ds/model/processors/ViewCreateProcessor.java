/*
 * Copyright (c) 2017 SAP and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * Contributors:
 * SAP - initial API and implementation
 */

package org.eclipse.dirigible.database.ds.model.processors;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.eclipse.dirigible.database.ds.model.DataStructureViewModel;
import org.eclipse.dirigible.database.sql.SqlFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: Auto-generated Javadoc
/**
 * The Class ViewCreateProcessor.
 */
public class ViewCreateProcessor {

	/** The Constant logger. */
	private static final Logger logger = LoggerFactory.getLogger(ViewCreateProcessor.class);

	/**
	 * Execute.
	 *
	 * @param connection the connection
	 * @param viewModel the view model
	 * @throws SQLException the SQL exception
	 */
	public static void execute(Connection connection, DataStructureViewModel viewModel) throws SQLException {
		logger.info("Processing Create View: " + viewModel.getName());
		if (!SqlFactory.getNative(connection).exists(connection, viewModel.getName())) {
			String sql = SqlFactory.getNative(connection).create().view(viewModel.getName()).asSelect(viewModel.getQuery()).build();
			Statement statement = connection.createStatement();
			try {
				logger.info(sql);
				statement.executeUpdate(sql);
			} catch (SQLException e) {
				logger.error(sql);
				logger.error(e.getMessage(), e);
			} finally {
				if (statement != null) {
					statement.close();
				}
			}
		}
	}

}