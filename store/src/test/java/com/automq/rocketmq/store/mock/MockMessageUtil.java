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

package com.automq.rocketmq.store.mock;

import com.automq.rocketmq.common.model.generated.KeyValue;
import com.automq.rocketmq.common.model.generated.Message;
import com.google.flatbuffers.FlatBufferBuilder;
import java.nio.ByteBuffer;

public class MockMessageUtil {
    public static ByteBuffer buildMessage() {
        return buildMessage(1,1,"");
    }

    public static ByteBuffer buildMessage(long topicId, int queueId, String tag) {
        // Build properties for message
        FlatBufferBuilder builder = new FlatBufferBuilder();
        int key = builder.createString("foo_key");
        int value = builder.createString("foo_value");
        int properties = KeyValue.createKeyValue(builder, key, value);

        // Build the payload for message
        int payload = Message.createPayloadVector(builder, new byte[1024]);

        int root = Message.createMessage(builder, topicId, queueId, builder.createString(tag), properties, payload);
        builder.finish(root);
        return builder.dataBuffer();
    }
}