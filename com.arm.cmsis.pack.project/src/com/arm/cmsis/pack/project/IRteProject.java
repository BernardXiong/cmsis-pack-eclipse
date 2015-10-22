/*******************************************************************************
* Copyright (c) 2015 ARM Ltd. and others
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
* ARM Ltd and ARM Germany GmbH - Initial API and implementation
*******************************************************************************/

package com.arm.cmsis.pack.project;


import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import com.arm.cmsis.pack.build.settings.RteToolChainAdapterInfo;
import com.arm.cmsis.pack.configuration.IRteConfiguration;
import com.arm.cmsis.pack.info.ICpFileInfo;

/**
 * Interface defining a CMSIS RTE project than manages RTE Configurations  
 */
public interface IRteProject {

	/**
	 * Returns project name
	 * @return project name
	 */
	String getName();

	/**
	 * Sets project name
	 * @param set new project name
	 */
	void setName(String name);

	
	/**
	 * Returns IProject resource associated with RTE project
	 * @return IProject associated with RTE project
	 */
	IProject getProject();

	/**
	 *  Destroys the IRteProject
	 */
	void destroy();
	
		
	/**
	 * Returns project's RTE configuration 
	 * @return IRteConfiguration if exists
	 */
	IRteConfiguration getRteConfiguration();


	/**
	 * Returns active RTE configuration name
	 * @return configuration filename or null if no active configuration exists 
	 */
	String getRteConfigurationName();


	/**
	 * Sets RTE configuration 
	 * @param rteConfigName configuration name 
	 * @param rteConfig IRteConfiguration
	 */
	void setRteConfiguration(String rteConfigName, IRteConfiguration rteConfig);

	
	/**
	 * Returns RTE-related information stored in .cproject file   
	 * @return RteProjectStorage
	 */
	public RteProjectStorage getProjectStorage();
	
	
	/**
	 * Returns RteToolChainAdapterInfo used by project
	 * @return RteToolChainAdapterInfo used by project
	 */
	RteToolChainAdapterInfo getToolChainAdapterInfo();

	/**
	 * Sets RteToolChainAdapterInfo to be used by project
	 * @param toolChainAdapterInfo RteToolChainAdapterInfo to be used by project
	 */
	void setToolChainAdapterInfo(RteToolChainAdapterInfo toolChainAdapterInfo);
	
	
	/**
	 * Checks if file is used by the project RTE configuration   
	 * @param fileName project-relative file name
	 * @return true if file is used 
	 */
	boolean isFileUsed(String fileName);

	/**
	 * Returns ICpFileInfo associated with project file resource  
	 * @param fileName project-relative file name
	 * @return ICpFileInfo if exists
	 */
	ICpFileInfo getProjectFileInfo(String fileName);
	
	
	/**
	 * Loads project data, triggers reload and full update 
	 * @throws CoreException 
	 */
	void load() throws CoreException;

	/**
	 * Triggers reload of RTE configuration
	 */
	void reload();
	
	/**
	 * Saves project data  
	 * @throws CoreException 
	 */
	void save() throws CoreException;


	/**
	 *  Triggers asynchronous update of resources, dynamic files and toolchain settings.<br>
	 *  This method is called for a new project and after device change   
	 */
	void update();

	
	/**
	 * Checks if project is fully loaded and updated
	 * @return true if project update is completed
	 */
	boolean isUpdateCompleted();

	/**
	 * Sets project complete state (should be called from an object that updates this project)  
	 * @param completed flag indicating if update is completed 
	 */
	void setUpdateCompleted(boolean completed);
	
}