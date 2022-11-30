/*
 * Copyright 2022 the original author or authors
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
package org.springframework.data.couchbase.core.convert;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.convert.PropertyValueConverter;
import org.springframework.data.convert.ValueConversionContext;
import org.springframework.data.couchbase.core.mapping.CouchbaseDocument;
import org.springframework.data.couchbase.core.mapping.CouchbasePersistentProperty;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.model.ConvertingPropertyAccessor;
import org.springframework.util.Assert;

import com.couchbase.client.core.encryption.CryptoManager;
import com.couchbase.client.core.error.InvalidArgumentException;
import com.couchbase.client.java.encryption.annotation.Encrypted;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.json.JsonValue;

/**
 * Encrypt/Decrypted properties annotated with
 * 
 * @author Michael Reiche
 */
public class CryptoConverter implements
		PropertyValueConverter<Object, CouchbaseDocument, ValueConversionContext<? extends PersistentProperty<?>>> {

	CryptoManager cryptoManager;

	public CryptoConverter(CryptoManager cryptoManager) {
		this.cryptoManager = cryptoManager;
	}

	@Override
	public Object read(CouchbaseDocument value, ValueConversionContext<? extends PersistentProperty<?>> context) {
		byte[] decrypted = cryptoManager().decrypt(value.export());
		if (decrypted == null) {
			return null;
		}
		// it's decrypted to byte[]. Now figure out how to convert to the property type.
		return coerceToValueRead(decrypted, (CouchbaseConversionContext) context);
	}

	@Override
	public CouchbaseDocument write(Object value, ValueConversionContext<? extends PersistentProperty<?>> context) {
		CouchbaseConversionContext ctx = (CouchbaseConversionContext) context;
		CouchbasePersistentProperty property = ctx.getProperty();
		byte[] plainText = coerceToBytesWrite(property, ctx.getAccessor(), ctx);
		Map<String, Object> encrypted = cryptoManager().encrypt(plainText, ctx.getProperty().findAnnotation(Encrypted.class).encrypter());
		return new CouchbaseDocument().setContent(encrypted);
	}

	private Object coerceToValueRead(byte[] decrypted, CouchbaseConversionContext context) {
		CouchbasePersistentProperty property = context.getProperty();

		CustomConversions cnvs = context.getConverter().getConversions();
		ConversionService svc = context.getConverter().getConversionService();
		Class<?> type = property.getType();

		String decryptedString = new String(decrypted);
		if ("null".equals(decryptedString)) {
			return null;
		}
		/* this what we would do if we could use a JsonParser with a beanPropertyTypeRef
		    final JsonParser plaintextParser = p.getCodec().getFactory().createParser(plaintext);
		plaintextParser.setCodec(p.getCodec());
		
		return plaintextParser.readValueAs(beanPropertyTypeRef);
		 */

		if (!cnvs.isSimpleType(type) && !type.isArray()) {
			JsonObject jo = JsonObject.fromJson(decryptedString);
			CouchbaseDocument source = new CouchbaseDocument().setContent(jo);
			return context.getConverter().read(property.getTypeInformation(), source);
		} else {
			String jsonString = "{\"" + property.getFieldName() + "\":" + decryptedString + "}";
			try {
				CouchbaseDocument decryptedDoc = new CouchbaseDocument().setContent(JsonObject.fromJson(jsonString));
				return context.getConverter().getPotentiallyConvertedSimpleRead(decryptedDoc.get(property.getFieldName()),
						property);
			} catch (InvalidArgumentException | ConverterNotFoundException | ConversionFailedException e) {
				throw new RuntimeException(decryptedString, e);
			}
		}
	}

	private byte[] coerceToBytesWrite(CouchbasePersistentProperty property, ConvertingPropertyAccessor accessor,
			CouchbaseConversionContext context) {
		byte[] plainText;
		CustomConversions cnvs = context.getConverter().getConversions();

		Class<?> sourceType = property.getType();
		Class<?> targetType = cnvs.getCustomWriteTarget(property.getType()).orElse(null);
		Object value = context.getConverter().getPotentiallyConvertedSimpleWrite(property, accessor, false);
		if (value == null) { // null
			plainText = "null".getBytes(StandardCharsets.UTF_8);
		} else if (value.getClass().isArray()) { // array
			JsonArray ja;
			if (value.getClass().getComponentType().isPrimitive()) {
				ja = jaFromPrimitiveArray(value);
			} else {
				ja = jaFromObjectArray(value, context.getConverter());
			}
			plainText = ja.toBytes();
		} else if (cnvs.isSimpleType(sourceType)) { // simpleType
			String plainString = value != null ? value.toString() : null;
			if ((sourceType == String.class || targetType == String.class) || sourceType == Character.class
					|| sourceType == char.class || Enum.class.isAssignableFrom(sourceType)
					|| Locale.class.isAssignableFrom(sourceType)) {
				// TODO use jackson serializer here
				plainString = "\"" + plainString.replaceAll("\"", "\\\"") + "\"";
			}
			plainText = plainString.getBytes(StandardCharsets.UTF_8);
		} else { // an entity
			plainText = JsonObject.fromJson(context.read(value).toString().getBytes(StandardCharsets.UTF_8)).toBytes();
		}
		return plainText;
	}

	CryptoManager cryptoManager() {
		Assert.notNull(cryptoManager,
				"cryptoManager needed to encrypt/decrypt but it is null. Override needed for cryptoManager() method of "
						+ AbstractCouchbaseConverter.class.getName());
		return cryptoManager;
	}

	JsonArray jaFromObjectArray(Object value, MappingCouchbaseConverter converter) {
		CustomConversions cnvs = converter.getConversions();
		ConversionService svc = converter.getConversionService();
		JsonArray ja = JsonArray.ja();
		for (Object o : (Object[]) value) {
			ja.add(coerceToJson(o, cnvs, svc));
		}
		return ja;
	}

	JsonArray jaFromPrimitiveArray(Object value) {
		Class<?> component = value.getClass().getComponentType();
		JsonArray jArray;
		if (Long.TYPE.isAssignableFrom(component)) {
			jArray = jaLong((long[]) value);
		} else if (Integer.TYPE.isAssignableFrom(component)) {
			jArray = jaInt((int[]) value);
		} else if (Double.TYPE.isAssignableFrom(component)) {
			jArray = jaDouble((double[]) value);
		} else if (Float.TYPE.isAssignableFrom(component)) {
			jArray = jaFloat((float[]) value);
		} else if (Boolean.TYPE.isAssignableFrom(component)) {
			jArray = jaBoolean((boolean[]) value);
		} else if (Short.TYPE.isAssignableFrom(component)) {
			jArray = jaShort((short[]) value);
		} else if (Byte.TYPE.isAssignableFrom(component)) {
			jArray = jaByte((byte[]) value);
		} else if (Character.TYPE.isAssignableFrom(component)) {
			jArray = jaChar((char[]) value);
		} else {
			throw new RuntimeException("unhandled primitive array: " + component.getName());
		}
		return jArray;
	}

	JsonArray jaLong(long[] array) {
		JsonArray ja = JsonArray.ja();
		for (long t : array) {
			ja.add(t);
		}
		return ja;
	}

	JsonArray jaInt(int[] array) {
		JsonArray ja = JsonArray.ja();
		for (int t : array) {
			ja.add(t);
		}
		return ja;
	}

	JsonArray jaDouble(double[] array) {
		JsonArray ja = JsonArray.ja();
		for (double t : array) {
			ja.add(t);
		}
		return ja;
	}

	JsonArray jaFloat(float[] array) {
		JsonArray ja = JsonArray.ja();
		for (float t : array) {
			ja.add(t);
		}
		return ja;
	}

	JsonArray jaBoolean(boolean[] array) {
		JsonArray ja = JsonArray.ja();
		for (boolean t : array) {
			ja.add(t);
		}
		return ja;
	}

	JsonArray jaShort(short[] array) {
		JsonArray ja = JsonArray.ja();
		for (short t : array) {
			ja.add(t);
		}
		return ja;
	}

	JsonArray jaByte(byte[] array) {
		JsonArray ja = JsonArray.ja();
		for (byte t : array) {
			ja.add(t);
		}
		return ja;
	}

	JsonArray jaChar(char[] array) {
		JsonArray ja = JsonArray.ja();
		for (char t : array) {
			ja.add(String.valueOf(t));
		}
		return ja;
	}

	Object coerceToJson(Object o, CustomConversions cnvs, ConversionService svc) {
		if (o != null && o.getClass() == Optional.class) {
			o = ((Optional<?>) o).isEmpty() ? null : ((Optional) o).get();
		}
		Optional<Class<?>> clazz;
		if (o == null) {
			o = JsonValue.NULL;
		} else if ((clazz = cnvs.getCustomWriteTarget(o.getClass())).isPresent()) {
			o = svc.convert(o, clazz.get());
		} else if (JsonObject.checkType(o)) {
			// The object is of an acceptable type
		} else if (Number.class.isAssignableFrom(o.getClass())) {
			if (o.toString().contains(".")) {
				o = ((Number) o).doubleValue();
			} else {
				o = ((Number) o).longValue();
			}
		} else if (Character.class.isAssignableFrom(o.getClass())) {
			o = ((Character) o).toString();
		} else if (Enum.class.isAssignableFrom(o.getClass())) {
			o = ((Enum) o).name();
		} else { // punt
			o = o.toString();
		}
		return o;
	}
}
