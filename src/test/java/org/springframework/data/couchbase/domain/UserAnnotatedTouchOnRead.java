/*
 * Copyright 2020-2023 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.couchbase.domain;

import com.couchbase.client.java.json.JsonObject;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.couchbase.core.mapping.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Annoted User entity for tests
 *
 * @author Michael Reiche
 */

@Document(expiry = 1, touchOnRead = true)
public class UserAnnotatedTouchOnRead extends User implements Serializable {

	JsonNode custom = new ObjectNode(JsonNodeFactory.instance);
	private void writeObject(ObjectOutputStream out) throws IOException {
		out.defaultWriteObject();
		if(custom== null){
			out.writeBoolean(false);
		} else {
			out.writeBoolean(true);
			new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false).writeValue((OutputStream)out, custom);
		}
	}

	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		if(in.readBoolean()){
			this.custom = new ObjectMapper().configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false).readValue((InputStream)in, JsonNode.class);
		}
	}
	public UserAnnotatedTouchOnRead(String id, String firstname, String lastname) {
		super(id, firstname, lastname);
	}
}
