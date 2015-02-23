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
package org.jclouds.shipyard.domain.engines;

import org.jclouds.javax.annotation.Nullable;
import org.jclouds.json.SerializedNames;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class AddEngine {

   public abstract String local();
   
   @Nullable abstract String sslCert();
   
   @Nullable abstract String sslKey();
   
   @Nullable abstract String caCert();
   
   public abstract EngineSettingsInfo engine();
   
   AddEngine() {
   }

   @SerializedNames({ "local", "ssl_cert", "ssl_key", "ca_cert", "engine" })
   public static AddEngine create(String local, String sslCert, 
                                 String sslKey, String caCert, EngineSettingsInfo engine) {
      return new AutoValue_AddEngine(local, sslCert, sslKey, caCert, engine);
   }
}
