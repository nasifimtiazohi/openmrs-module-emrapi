/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.emrapi.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.jms.MapMessage;
import javax.jms.Message;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Patient;
import org.openmrs.User;
import org.openmrs.api.APIException;
import org.openmrs.api.UserService;
import org.openmrs.api.context.Context;
import org.openmrs.event.EventListener;
import org.openmrs.module.emrapi.EmrApiConstants;
import org.openmrs.module.emrapi.EmrApiProperties;
import org.openmrs.module.emrapi.utils.GeneralUtils;
import org.openmrs.util.PrivilegeConstants;

/**
 * Listens for patient viewed events, the patient found in the message payload gets added to the
 * last viewed patients user property of the specified user,
 */
public class PatientViewedEventListener implements EventListener {
	
	protected final Log log = LogFactory.getLog(getClass());
	
	/**
	 * @see EventListener#onMessage(javax.jms.Message)
	 * @param message
	 * @should add the patient to the last viewed user property
	 * @should remove the first patient and add the new one to the start if the list is full
	 * @should not add a duplicate and should move the existing patient to the start
	 * @should not remove any patient if a duplicate is added to a full list
	 */
	@Override
	public void onMessage(Message message) {
		MapMessage mapMessage = (MapMessage) message;
		Context.openSession();
		try {
			String patientUuid = mapMessage.getString(EmrApiConstants.EVENT_KEY_PATIENT_UUID);
			String userUuid = mapMessage.getString(EmrApiConstants.EVENT_KEY_USER_UUID);
			
			Context.addProxyPrivilege(PrivilegeConstants.VIEW_PATIENTS);
			Context.addProxyPrivilege(PrivilegeConstants.VIEW_USERS);
			Context.addProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			
			Patient patientToAdd = Context.getPatientService().getPatientByUuid(patientUuid);
			if (patientToAdd == null)
				throw new APIException("failed to find patient with uuid:" + patientUuid);
			
			if (patientToAdd.getId() == null) {
				return;
			}
			UserService userService = Context.getUserService();
			User user = userService.getUserByUuid(userUuid);
			if (user != null && patientToAdd != null) {
				EmrApiProperties emrProperties = Context.getRegisteredComponents(EmrApiProperties.class).iterator().next();
				Integer limit = emrProperties.getLastViewedPatientSizeLimit();
				List<Integer> patientIds = new ArrayList<Integer>(limit);
				if (limit > 0) {
					List<Patient> lastViewedPatients = GeneralUtils.getLastViewedPatients(user);
					patientIds.add(patientToAdd.getId());
					for (Patient p : lastViewedPatients) {
						if (patientIds.size() == limit)
							break;
						if (patientIds.contains(p.getId()))
							continue;
						
						patientIds.add(p.getId());
					}
					
					Collections.reverse(patientIds);
				}
				//we can't update the user by calling userService.saveUser or userService.setUserProperty
				//because the api requires that the logged in user has all the privileges the user has
				//userService.setUserProperty(user, EmrApiConstants.USER_PROPERTY_NAME_LAST_VIEWED_PATIENT_IDS,StringUtils.join(patientIds, ","));
				Context.getAdministrationService().executeSQL(
				    "update user_property set property_value='" + StringUtils.join(patientIds, ",") + "' where property='"
				            + EmrApiConstants.USER_PROPERTY_NAME_LAST_VIEWED_PATIENT_IDS + "' and user_id=" + user.getId(),
				    false);
			}
		}
		catch (Exception e) {
			log.error("Failed to process patient viewed event message", e);
		}
		finally {
			Context.removeProxyPrivilege(PrivilegeConstants.VIEW_PATIENTS);
			Context.removeProxyPrivilege(PrivilegeConstants.VIEW_USERS);
			Context.removeProxyPrivilege(PrivilegeConstants.SQL_LEVEL_ACCESS);
			
			Context.closeSession();
		}
	}
}