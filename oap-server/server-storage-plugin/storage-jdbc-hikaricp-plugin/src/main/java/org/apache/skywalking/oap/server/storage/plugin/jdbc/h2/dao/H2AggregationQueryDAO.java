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
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.dao;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.skywalking.oap.server.core.analysis.DownSampling;
import org.apache.skywalking.oap.server.core.analysis.manual.endpoint.EndpointTraffic;
import org.apache.skywalking.oap.server.core.analysis.manual.instance.InstanceTraffic;
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics;
import org.apache.skywalking.oap.server.core.query.enumeration.Order;
import org.apache.skywalking.oap.server.core.query.input.Duration;
import org.apache.skywalking.oap.server.core.query.input.TopNCondition;
import org.apache.skywalking.oap.server.core.query.type.KeyValue;
import org.apache.skywalking.oap.server.core.query.type.SelectedRecord;
import org.apache.skywalking.oap.server.core.query.type.TopNEntity;
import org.apache.skywalking.oap.server.core.storage.query.IAggregationQueryDAO;
import org.apache.skywalking.oap.server.library.client.jdbc.hikaricp.JDBCHikariCPClient;

public class H2AggregationQueryDAO implements IAggregationQueryDAO {

    @Getter(AccessLevel.PROTECTED)
    private JDBCHikariCPClient h2Client;

    public H2AggregationQueryDAO(JDBCHikariCPClient h2Client) {
        this.h2Client = h2Client;
    }

    @Override
    public List<SelectedRecord> sortMetrics(final TopNCondition metrics,
                                            final String valueColumnName,
                                            final Duration duration,
                                            List<KeyValue> additionalConditions) throws IOException {
        StringBuilder sql = new StringBuilder();
        List<Object> conditions = new ArrayList<>(10);
        sql.append("select * from (select avg(")
           .append(valueColumnName)
           .append(") value,")
           .append(Metrics.ENTITY_ID)
           .append(" from ")
           .append(metrics.getName())
           .append(" where ");
        this.setTimeRangeCondition(sql, conditions, duration.getStartTimeBucket(), duration.getEndTimeBucket());
        if (additionalConditions != null) {
            additionalConditions.forEach(condition -> {
                sql.append(" and ").append(condition.getKey()).append("=?");
                conditions.add(condition.getValue());
            });
        }
        sql.append(" group by ").append(Metrics.ENTITY_ID);
        sql.append(") order by value ")
           .append(metrics.getOrder().equals(Order.ASC) ? "asc" : "desc")
           .append(" limit ")
           .append(metrics.getTopN());
        List<SelectedRecord> topNEntities = new ArrayList<>();
        try (Connection connection = h2Client.getConnection();
             ResultSet resultSet = h2Client.executeQuery(
                 connection, sql.toString(), conditions.toArray(new Object[0]))) {
            while (resultSet.next()) {
                SelectedRecord topNEntity = new SelectedRecord();
                topNEntity.setId(resultSet.getString(Metrics.ENTITY_ID));
                topNEntity.setValue(resultSet.getString("value"));
                topNEntities.add(topNEntity);
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
        return topNEntities;
    }

    protected void setTimeRangeCondition(StringBuilder sql, List<Object> conditions, long startTimestamp,
                                         long endTimestamp) {
        sql.append(Metrics.TIME_BUCKET).append(" >= ? and ").append(Metrics.TIME_BUCKET).append(" <= ?");
        conditions.add(startTimestamp);
        conditions.add(endTimestamp);
    }
}
