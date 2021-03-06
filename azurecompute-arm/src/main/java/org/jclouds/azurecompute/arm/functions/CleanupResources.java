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
package org.jclouds.azurecompute.arm.functions;

import static com.google.common.base.Predicates.notNull;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static org.jclouds.azurecompute.arm.config.AzureComputeProperties.TIMEOUT_RESOURCE_DELETED;
import static org.jclouds.util.Closeables2.closeQuietly;

import java.net.URI;
import java.util.List;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.azurecompute.arm.AzureComputeApi;
import org.jclouds.azurecompute.arm.compute.functions.LocationToResourceGroupName;
import org.jclouds.azurecompute.arm.domain.IdReference;
import org.jclouds.azurecompute.arm.domain.IpConfiguration;
import org.jclouds.azurecompute.arm.domain.NetworkInterfaceCard;
import org.jclouds.azurecompute.arm.domain.RegionAndId;
import org.jclouds.azurecompute.arm.domain.StorageServiceKeys;
import org.jclouds.azurecompute.arm.domain.VirtualMachine;
import org.jclouds.azurecompute.arm.util.BlobHelper;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.logging.Logger;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

@Singleton
public class CleanupResources implements Function<String, Boolean> {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   protected final AzureComputeApi api;
   private final Predicate<URI> resourceDeleted;
   private final StorageProfileToStorageAccountName storageProfileToStorageAccountName;
   private final LocationToResourceGroupName locationToResourceGroupName;

   @Inject
   CleanupResources(AzureComputeApi azureComputeApi, @Named(TIMEOUT_RESOURCE_DELETED) Predicate<URI> resourceDeleted,
         StorageProfileToStorageAccountName storageProfileToStorageAccountName,
         LocationToResourceGroupName locationToResourceGroupName) {
      this.api = azureComputeApi;
      this.resourceDeleted = resourceDeleted;
      this.storageProfileToStorageAccountName = storageProfileToStorageAccountName;
      this.locationToResourceGroupName = locationToResourceGroupName;
   }

   @Override
   public Boolean apply(final String id) {
      RegionAndId regionAndId = RegionAndId.fromSlashEncoded(id);
      String group = locationToResourceGroupName.apply(regionAndId.region());
      
      VirtualMachine virtualMachine = api.getVirtualMachineApi(group).get(regionAndId.id());
      if (virtualMachine == null) {
         return true;
      }

      logger.debug(">> destroying %s ...", regionAndId.slashEncode());
      boolean vmDeleted = deleteVirtualMachine(group, virtualMachine);
      
      // We don't delete the network here, as it is global to the resource
      // group. It will be deleted when the resource group is deleted

      for (String nicName : getNetworkCardInterfaceNames(virtualMachine)) {
         NetworkInterfaceCard nic = api.getNetworkInterfaceCardApi(group).get(nicName);
         Iterable<String> publicIps = getPublicIps(group, nic);

         logger.debug(">> destroying nic %s...", nicName);
         URI nicDeletionURI = api.getNetworkInterfaceCardApi(group).delete(nicName);
         resourceDeleted.apply(nicDeletionURI);

         for (String publicIp : publicIps) {
            logger.debug(">> deleting public ip nic %s...", publicIp);
            api.getPublicIPAddressApi(group).delete(publicIp);
         }
      }

      String storageAccountName = storageProfileToStorageAccountName.apply(virtualMachine.properties().storageProfile());
      StorageServiceKeys keys = api.getStorageAccountApi(group).getKeys(storageAccountName);

      // Remove the virtual machine files
      logger.debug(">> deleting virtual machine disk storage...");
      BlobHelper blobHelper = new BlobHelper(storageAccountName, keys.key1());
      try {
         blobHelper.deleteContainerIfExists("vhds");

         if (!blobHelper.customImageExists()) {
            logger.debug(">> deleting storage account %s...", storageAccountName);
            api.getStorageAccountApi(group).delete(storageAccountName);
         } else {
            logger.debug(">> the storage account contains custom images. Will not delete it!");
         }
      } finally {
         closeQuietly(blobHelper);
      }

      deleteResourceGroupIfEmpty(group);

      return vmDeleted;
   }

   public void deleteResourceGroupIfEmpty(String group) {
      if (api.getVirtualMachineApi(group).list().isEmpty() 
            && api.getStorageAccountApi(group).list().isEmpty()
            && api.getNetworkInterfaceCardApi(group).list().isEmpty()
            && api.getPublicIPAddressApi(group).list().isEmpty()) {
         logger.debug(">> the resource group %s is empty. Deleting...", group);
         resourceDeleted.apply(api.getResourceGroupApi().delete(group));
      }
   }

   private Iterable<String> getPublicIps(String group, NetworkInterfaceCard nic) {
      return transform(
            filter(transform(nic.properties().ipConfigurations(), new Function<IpConfiguration, IdReference>() {
               @Override
               public IdReference apply(IpConfiguration input) {
                  return input.properties().publicIPAddress();
               }
            }), notNull()), new Function<IdReference, String>() {
               @Override
               public String apply(IdReference input) {
                  return Iterables.getLast(Splitter.on("/").split(input.id()));
               }
            });
   }

   private List<String> getNetworkCardInterfaceNames(VirtualMachine virtualMachine) {
      List<String> nics = Lists.newArrayList();
      for (IdReference idReference : virtualMachine.properties().networkProfile().networkInterfaces()) {
         nics.add(Iterables.getLast(Splitter.on("/").split(idReference.id())));
      }
      return nics;
   }

   private boolean deleteVirtualMachine(String group, VirtualMachine virtualMachine) {
      return resourceDeleted.apply(api.getVirtualMachineApi(group).delete(virtualMachine.name()));
   }

}
