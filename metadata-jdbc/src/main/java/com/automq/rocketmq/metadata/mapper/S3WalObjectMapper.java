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

package com.automq.rocketmq.metadata.mapper;

import com.automq.rocketmq.metadata.dao.S3WalObject;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface S3WalObjectMapper {

    int create(S3WalObject s3WALObject);

    S3WalObject getByObjectId(long objectId);

    int delete(@Param("objectId") Long objectId,
        @Param("nodeId") Integer nodeId,
        @Param("sequenceId") Long sequenceId);

    List<S3WalObject> list(@Param("nodeId") Integer nodeId, @Param("sequenceId") Long sequenceId);

    int commit(S3WalObject s3WALObject);

    boolean streamExclusive(@Param("nodeId") int nodeId, @Param("streamId") long streamId);
}
