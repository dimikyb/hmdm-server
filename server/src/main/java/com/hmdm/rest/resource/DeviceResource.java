/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.hmdm.notification.persistence.NotificationDAO;
import com.hmdm.persistence.domain.ApplicationSetting;
import com.hmdm.rest.json.view.devicelist.DeviceListView;
import com.hmdm.rest.json.view.devicelist.DeviceView;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceSearchRequest;
import com.hmdm.rest.json.PaginatedData;
import com.hmdm.rest.json.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Api(tags = {"Device"}, authorizations = {@Authorization("Bearer Token")})
@Singleton
@Path("/private/devices")
public class DeviceResource {

    private static final Logger log = LoggerFactory.getLogger(DeviceResource.class);

    private DeviceDAO deviceDAO;
    private ConfigurationDAO configurationDAO;
    private NotificationDAO notificationDAO;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public DeviceResource() {
    }

    @Inject
    public DeviceResource(DeviceDAO deviceDAO,
                          ConfigurationDAO configurationDAO,
                          NotificationDAO notificationDAO) {
        this.deviceDAO = deviceDAO;
        this.configurationDAO = configurationDAO;
        this.notificationDAO = notificationDAO;

    }

    // =================================================================================================================
    @ApiOperation(
            value = "Search devices",
            notes = "Search devices meeting the specified filter value",
            response = DeviceListView.class
    )
    @POST
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllDevices(DeviceSearchRequest request) {
        PaginatedData<Device> devices = this.deviceDAO.getAllDevices(request);
        Map<Integer, List<Application>> configIdToApplicationsMap = new HashMap<>();
        Map<Integer, Configuration> configIdToConfigurationsMap = new HashMap<>();
        for (Device device : devices.getItems()) {
            final Integer deviceConfigurationId = device.getConfigurationId();

            if (!configIdToApplicationsMap.containsKey(deviceConfigurationId)) {
                configIdToApplicationsMap.put(deviceConfigurationId, this.configurationDAO.getConfigurationApplications(deviceConfigurationId));
            }

            if (!configIdToConfigurationsMap.containsKey(deviceConfigurationId)) {
                Configuration configuration = new Configuration();
                configuration.setId(deviceConfigurationId);
                configuration.setName(device.getConfigName());
                configuration.setApplications(configIdToApplicationsMap.get(deviceConfigurationId));
                configIdToConfigurationsMap.put(deviceConfigurationId, configuration);
            }

            device.setConfiguration(configIdToConfigurationsMap.get(deviceConfigurationId));
        }

        final List<DeviceView> deviceViews = devices.getItems().stream().map(DeviceView::new).collect(Collectors.toList());
        PaginatedData<DeviceView> devicesPage = new PaginatedData<>(deviceViews, devices.getTotalItemsCount());

        DeviceListView view = new DeviceListView(configIdToConfigurationsMap.values(), devicesPage);

        return Response.OK(view);

    }

    // =================================================================================================================
    @ApiOperation(
            value = "Create or update device",
            notes = "Create a new device (if id is not provided) or update existing one otherwise."
    )
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateDevice(Device device) {
        try {
            final boolean canEditDevices = SecurityContext.get().hasPermission("edit_devices");

            if (!canEditDevices) {
                log.error("Unauthorized attempt to create or edit device",
                        SecurityException.onCustomerDataAccessViolation(device.getId(), "device"));
                return Response.PERMISSION_DENIED();
            }

            Device dbDevice = this.deviceDAO.getDeviceByNumber(device.getNumber());
            if (dbDevice != null && !dbDevice.getId().equals(device.getId())) {
                log.error("A different device with same number exists: {}", dbDevice);
                return Response.ERROR();
            } else {
                dbDevice = this.deviceDAO.getDeviceById(device.getId());
                if (device.getId() != null) {
                    if (dbDevice != null) {
                        this.deviceDAO.updateDevice(device);
                    }
                } else if (device.getIds() != null) {
                    // This is a bulk request to update configurations for selected devices
                    Iterator it = device.getIds().iterator();

                    while(it.hasNext()) {
                        Integer id = (Integer)it.next();
                        dbDevice = this.deviceDAO.getDeviceById(id);
                        if (dbDevice != null) {
                            this.deviceDAO.updateDeviceConfiguration(id, device.getConfigurationId());
                        }
                    }
                } else {
                    device.setLastUpdate(0L);
                    this.deviceDAO.insertDevice(device);
                }

                return Response.OK();
            }
        } catch (Exception e) {
            log.error("Unexpected error when saving/creating device", e);
            return Response.ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Delete device",
            notes = "Delete an existing device"
    )
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeDevice(@PathParam("id") @ApiParam("Device ID") Integer id) {
        final boolean canEditDevices = SecurityContext.get().hasPermission("edit_devices");

        if (!(canEditDevices)) {
            log.error("Unauthorized attempt to delete device",
                    SecurityException.onCustomerDataAccessViolation(id, "device"));
            return Response.PERMISSION_DENIED();
        }

        this.deviceDAO.removeDeviceById(id);
        return Response.OK();
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Get device application settings",
            notes = "Get application settings set at device level"
    )
    @GET
    @Path("/{id}/applicationSettings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDeviceApplicationSettings(@PathParam("id") @ApiParam("Device ID") Integer id) {
        try {
            final List<ApplicationSetting> deviceApplicationSettings = this.deviceDAO.getDeviceApplicationSettings(id);
            return Response.OK(deviceApplicationSettings);
        } catch (Exception e) {
            log.error("Failed to retrieve the application settings for device #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save device application settings",
            notes = "Save application settings set at device level"
    )
    @POST
    @Path("/{id}/applicationSettings")
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveDeviceApplicationSettings(@PathParam("id") @ApiParam("Device ID") Integer id,
                                                  List<ApplicationSetting> applicationSettings) {
        try {
            this.deviceDAO.saveDeviceApplicationSettings(id, applicationSettings);
            return Response.OK();
        } catch (Exception e) {
            log.error("Failed to save the application settings for device #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Notify device on update",
            notes = "Sends a notification to device on application settings update",
            response = Void.class
    )
    @POST
    @Path("/{id}/applicationSettings/notify")
    @Produces(MediaType.APPLICATION_JSON)
    public Response notifyDevicesOnUpdate(@PathParam("id") Integer id) {
        try {
            this.notificationDAO.notifyDeviceOnApplicationSettingUpdate(id);
            return Response.OK();
        } catch (Exception e) {
            log.error("Failed to send notification on application settings update to device #{}", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    // =================================================================================================================
    @ApiOperation(
            value = "Save device description",
            notes = "Updates existing device description"
    )
    @POST
    @Path("/{id}/description")
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveDeviceDescription(@PathParam("id") @ApiParam("Device ID") Integer deviceId,
                                          String newDeviceDescription) {
        try {
            final boolean canEditDeviceDescription = SecurityContext.get().hasPermission("edit_device_desc");

            if (!canEditDeviceDescription) {
                log.error("Unauthorized attempt to edit device description",
                        SecurityException.onCustomerDataAccessViolation(deviceId, "device"));
                return Response.PERMISSION_DENIED();
            }

            this.deviceDAO.updateDeviceDescription(deviceId, newDeviceDescription);
            return Response.OK();
        } catch (Exception e) {
            log.error("Failed to save the description for device #{}", deviceId, e);
            return Response.INTERNAL_ERROR();
        }
    }
}