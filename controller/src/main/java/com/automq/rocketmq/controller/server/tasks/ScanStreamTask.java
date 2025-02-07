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

package com.automq.rocketmq.controller.server.tasks;

import com.automq.rocketmq.controller.exception.ControllerException;
import com.automq.rocketmq.controller.MetadataStore;
import com.automq.rocketmq.metadata.dao.Stream;
import com.automq.rocketmq.metadata.dao.StreamCriteria;
import com.automq.rocketmq.metadata.mapper.StreamMapper;
import java.util.List;
import org.apache.ibatis.session.SqlSession;

public class ScanStreamTask extends ScanTask {
    public ScanStreamTask(MetadataStore metadataStore) {
        super(metadataStore);
    }

    @Override
    public void process() throws ControllerException {
        try (SqlSession session = metadataStore.openSession()) {
            StreamMapper mapper = session.getMapper(StreamMapper.class);
            StreamCriteria criteria = StreamCriteria.newBuilder()
                .withUpdateTime(lastScanTime)
                .build();
            List<Stream> streams = mapper.byCriteria(criteria);
            metadataStore.applyStreamChange(streams);

            for (Stream stream : streams) {
                if (null == lastScanTime || stream.getUpdateTime().after(lastScanTime)) {
                    lastScanTime = stream.getUpdateTime();
                }
            }
        }
    }
}
