/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jclouds.virtualbox.util;

import javax.annotation.Resource;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.compute.callables.RunScriptOnNode;
import org.jclouds.compute.callables.RunScriptOnNode.Factory;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.logging.Logger;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.util.Throwables2;
import org.virtualbox_4_1.IMachine;
import org.virtualbox_4_1.ISession;
import org.virtualbox_4_1.LockType;
import org.virtualbox_4_1.VBoxException;
import org.virtualbox_4_1.VirtualBoxManager;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;

/**
 * Utilities for executing functions on a VirtualBox machine.
 * 
 * @author Adrian Cole, Mattias Holmqvist, Andrea Turli
 */

@Singleton
public class MachineUtils {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   private final Supplier<VirtualBoxManager> manager;
   private final Factory scriptRunner;
   private final Supplier<NodeMetadata> host;

   @Inject
   public MachineUtils(Supplier<VirtualBoxManager> manager, RunScriptOnNode.Factory scriptRunner,
            Supplier<NodeMetadata> host) {
      super();
      this.manager = manager;
      this.scriptRunner = scriptRunner;
      this.host = host;
   }

   public ListenableFuture<ExecResponse> runScriptOnNode(NodeMetadata metadata, Statement statement,
            RunScriptOptions options) {
      return scriptRunner.submit(metadata, statement, options);
   }

   /**
    * Locks the machine and executes the given function using the machine matching the given id.
    * Since the machine is locked it is possible to perform some modifications to the IMachine.
    * <p/>
    * Unlocks the machine before returning.
    * 
    * @param machineId
    *           the id of the machine
    * @param function
    *           the function to execute
    * @return the result from applying the function to the machine.
    */
   public <T> T writeLockMachineAndApply(final String machineId, final Function<IMachine, T> function) {
      return lockSessionOnMachineAndApply(machineId, LockType.Write, new Function<ISession, T>() {

         @Override
         public T apply(ISession session) {
            return function.apply(session.getMachine());
         }

         @Override
         public String toString() {
            return function.toString();
         }

      });
   }
   
   public <T> T writeLockMachineAndApplyToSession(final String machineId, final Function<ISession, T> function) {
      return lockSessionOnMachineAndApply(machineId, LockType.Write, function);
   }
   
   public <T> T readLockMachineAndApply(final String machineId, final Function<IMachine, T> function) {
      return lockSessionOnMachineAndApply(machineId, LockType.Shared, new Function<ISession, T>() {

         @Override
         public T apply(ISession session) {
            return function.apply(session.getMachine());
         }

         @Override
         public String toString() {
            return function.toString();
         }

      });
   }
   
   public <T> T readLockMachineAndApplyToSession(final String machineId, final Function<ISession, T> function) {
      return lockSessionOnMachineAndApply(machineId, LockType.Shared, function);
   }
   

   /**
    * Locks the machine and executes the given function using the current session. Since the machine
    * is locked it is possible to perform some modifications to the IMachine.
    * <p/>
    * Unlocks the machine before returning.
    * 
    * @param type
    *           the kind of lock to use when initially locking the machine.
    * @param machineId
    *           the id of the machine
    * @param function
    *           the function to execute
    * @return the result from applying the function to the session.
    */
   private <T> T lockSessionOnMachineAndApply(String machineId, LockType type, Function<ISession, T> function) {
      try {
         int retries = 5;
         int count = 0;
         ISession session;
         while (true) {
            try {
               session = manager.get().getSessionObject();
               IMachine immutableMachine = manager.get().getVBox().findMachine(machineId);
               immutableMachine.lockMachine(session, type);
               break;
            } catch (VBoxException e) {
               VBoxException vbex = Throwables2.getFirstThrowableOfType(e, VBoxException.class);
               if (vbex != null && machineNotFoundException(vbex)){
                  return null;
               }
               count++;
               logger.warn("Could not lock machine (try %i of %i). Error: %s", retries, count, e.getMessage());
               if (count == retries){
                  throw new RuntimeException(String.format("error locking %s with %s lock: %s", machineId,
                           type, e.getMessage()), e);   
               }
               try {
                  Thread.sleep(1000L);
               } catch (InterruptedException e1) {
               }
            }
         }
         try {
            return function.apply(session);
         } finally {
            session.unlockMachine();
         }
      } catch (VBoxException e) {
         throw new RuntimeException(String.format("error applying %s to %s with %s lock: %s", function, machineId,
                  type, e.getMessage()), e);
      }
   }

   /**
    * @param machineId
    * @param function
    * @return
    */
   public <T> T applyForMachine(final String machineId, final Function<IMachine, T> function) {
      final IMachine immutableMachine = manager.get().getVBox().findMachine(machineId);
      return new Function<IMachine, T>() {
         @Override
         public T apply(IMachine machine) {
            return function.apply(machine);
         }

         @Override
         public String toString() {
            return function.toString();
         }
      }.apply(immutableMachine);
   }

   public static boolean machineNotFoundException(VBoxException e) {
      return e.getMessage().contains("VirtualBox error: Could not find a registered machine named ")
               || e.getMessage().contains("Could not find a registered machine with UUID {");
   }
}
