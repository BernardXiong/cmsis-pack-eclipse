package com.arm.cmsis.pack.project;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.cdt.managedbuilder.core.IConfiguration;
import org.eclipse.cdt.managedbuilder.core.IManagedBuildInfo;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;

import com.arm.cmsis.pack.CpPlugIn;
import com.arm.cmsis.pack.ICpEnvironmentProvider;
import com.arm.cmsis.pack.ICpPackInstaller;
import com.arm.cmsis.pack.build.IBuildSettings;
import com.arm.cmsis.pack.build.IMemorySettings;
import com.arm.cmsis.pack.build.settings.ILinkerScriptGenerator;
import com.arm.cmsis.pack.build.settings.IRteToolChainAdapter;
import com.arm.cmsis.pack.common.CmsisConstants;
import com.arm.cmsis.pack.configuration.IRteConfiguration;
import com.arm.cmsis.pack.configuration.RteConfiguration;
import com.arm.cmsis.pack.data.CpPack;
import com.arm.cmsis.pack.data.ICpFile;
import com.arm.cmsis.pack.data.ICpItem;
import com.arm.cmsis.pack.data.ICpPack;
import com.arm.cmsis.pack.data.ICpPack.PackState;
import com.arm.cmsis.pack.data.ICpPackCollection;
import com.arm.cmsis.pack.enums.EFileCategory;
import com.arm.cmsis.pack.enums.EFileRole;
import com.arm.cmsis.pack.generic.IAttributes;
import com.arm.cmsis.pack.info.ICpConfigurationInfo;
import com.arm.cmsis.pack.info.ICpDeviceInfo;
import com.arm.cmsis.pack.info.ICpFileInfo;
import com.arm.cmsis.pack.info.ICpPackInfo;
import com.arm.cmsis.pack.parser.CpConfigParser;
import com.arm.cmsis.pack.project.ui.RteProjectDecorator;
import com.arm.cmsis.pack.project.utils.ProjectUtils;
import com.arm.cmsis.pack.ui.CpPlugInUI;
import com.arm.cmsis.pack.ui.console.RteConsole;
import com.arm.cmsis.pack.utils.Utils;

public class RteProjectUpdater extends WorkspaceJob {


	public static final int LOAD_CONFIGS = 0x01;
	public static final int UPDATE_TOOLCHAIN = 0x02; // forces update of all relevant toolchain settings
	public static final int CLEANUP_RTE_FILES = 0x04; // delete excluded RTE config files

	protected IRteProject rteProject;
	protected IProject project;
	protected IProgressMonitor monitor = null;

	protected int updateFlags = 0;
	protected boolean bLoadConfigs = false;
	protected boolean bForceUpdateToolchain = false;
	protected boolean bSaveProject = false;
	protected boolean bDeleteConfigFiles = false;
	protected RteConsole rteConsole = null;

	public RteProjectUpdater(IRteProject rteProject, int updateFlags) {
		super("RTE Project Updater"); //$NON-NLS-1$
		this.rteProject = rteProject;
		this.project = rteProject.getProject();
		this.updateFlags = updateFlags;
		bLoadConfigs = (updateFlags & LOAD_CONFIGS) == LOAD_CONFIGS;
		bForceUpdateToolchain = (updateFlags & UPDATE_TOOLCHAIN) == UPDATE_TOOLCHAIN;
		bDeleteConfigFiles = (updateFlags & CLEANUP_RTE_FILES) == CLEANUP_RTE_FILES;

		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		setRule(workspace.getRoot()); // ensures synch update

		rteConsole = RteConsole.openConsole(project);
	}

	@Override
	public IStatus runInWorkspace(IProgressMonitor monitor) {
		if (project == null) {
			Status status = new Status(IStatus.ERROR, CpPlugInUI.PLUGIN_ID,
					Messages.RteProjectUpdater_ErrorProjectIsNull);
			return status;
		}
		this.monitor = monitor;
		bSaveProject = false;
		Status status = null;
		try {
			long startTime = System.currentTimeMillis();
			String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date(startTime)); //$NON-NLS-1$
			String msg = timestamp + " **** " + Messages.RteProjectUpdater_UpdatingProject + " " + project.getName(); //$NON-NLS-1$ //$NON-NLS-2$
			rteConsole.outputInfo(msg);
			if (bLoadConfigs) {
				rteConsole.outputInfo(Messages.RteProjectUpdater_LoadingRteConfiguration);
				loadConfigFile();
			}
			rteConsole.outputInfo(Messages.RteProjectUpdater_UpdatingResources);
			addResources();
			removeResources();

			updateRteComponentsH();

			rteConsole.outputInfo(Messages.RteProjectUpdater_UpdatingBuildSettings);
			updateBuildSettings(bForceUpdateToolchain);

			if (bSaveProject) {
				rteProject.save();
			}
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			updateIndex();

		} catch (CoreException e) {
			status = new Status(e.getStatus().getSeverity(), CpPlugInUI.PLUGIN_ID,
					Messages.RteProjectUpdater_ErrorUpdatingRteProject, e);
		} catch (Exception e) {
			e.printStackTrace();
			status = new Status(IStatus.ERROR, CpPlugInUI.PLUGIN_ID, Messages.RteProjectUpdater_ErrorUpdatingRteProject,
					e);
		} finally {
			rteProject.setUpdateCompleted(true);
		}
		if (status != null) {
			rteConsole.outputError(Messages.RteProjectUpdater_Fail);
			rteConsole.outputInfo(status.getMessage());
			IStatus[] statusArray = status.getChildren();
			if (statusArray != null && statusArray.length > 0) {
				for (IStatus s : statusArray) {
					rteConsole.outputInfo(s.getMessage());
				}
			}
		} else {
			rteConsole.outputInfo(Messages.RteProjectUpdater_Success);
			status = new Status(IStatus.OK, CpPlugInUI.PLUGIN_ID, Messages.RteProjectUpdater_ProjectUpdated);
		}
		rteConsole.output(CmsisConstants.EMPTY_STRING);
		RteProjectDecorator.refresh();

		return status;
	}

	protected void updateIndex() {
		rteProject.setUpdateCompleted(true);
		CpProjectPlugIn.getRteProjectManager().updateIndex(project);
	}

	protected void loadConfigFile() throws CoreException {
		String savedRteConfigName = rteProject.getRteConfigurationName();
		IRteConfiguration rteConf = loadRteConfiguration(savedRteConfigName);
		Collection<String> errors = rteConf.validate();
		if (errors == null || errors.isEmpty()) {
			return;
		}
		String msg = Messages.RteProjectUpdater_ErrorLoadinConfigFile + " '" + savedRteConfigName + "':"; //$NON-NLS-1$ //$NON-NLS-2$
		rteConsole.outputError(msg);
		for (String s : errors) {
			rteConsole.output(s);
			msg += System.lineSeparator() + s;
		}

		// install missing packs?
		final ICpPackInstaller packInstaller = CpPlugIn.getPackManager().getPackInstaller();
		final Collection<ICpPackInfo> missingPacks = rteConf.getMissingPacks();
		StringBuilder sb = new StringBuilder(System.lineSeparator());
		for (ICpPackInfo pi : missingPacks) {
			if (!packInstaller.isProcessing(pi.attributes())
					&& !hasInstalled(pi.attributes())) {
				sb.append(System.lineSeparator() + pi.getPackFamilyId());
			}
		}
		if (sb.length() > System.lineSeparator().length()) {	// sb always contains at first a System.lineSeparator
			sb.append(System.lineSeparator()).append(System.lineSeparator());
			final String message = NLS.bind(Messages.RteProjectUpdater_InstallMissinPacksMessage, rteProject.getName(),
					sb.toString());
			Display.getDefault().asyncExec(new Runnable() {
				@Override
				public void run() {
					boolean install = MessageDialog.openQuestion(Display.getDefault().getActiveShell(),
							NLS.bind(Messages.RteProjectUpdater_InstallMissinPacksTitle, rteProject.getName()),
							message);
					if (install) {
						for (ICpPackInfo pi : missingPacks) {
							packInstaller.installPack(pi.attributes());
						}
					}
				}
			});
		}

		Status status = new Status(IStatus.WARNING, CpPlugInUI.PLUGIN_ID, msg);
		throw new CoreException(status);
	}

	protected IRteConfiguration loadRteConfiguration(String savedRteConfigName) throws CoreException {
		if (savedRteConfigName == null || savedRteConfigName.isEmpty()) {
			return null;
		}

		String rteConfigName = project.getName() + CmsisConstants.DOT_RTECONFIG;
		// moving project can still left the old file
		IFile iFile = project.getFile(rteConfigName);
		if (!iFile.exists() || iFile.getLocation() == null) {
			// ensure file has the project name (e.g. after rename)
			if (!rteConfigName.equals(savedRteConfigName)) {
				iFile = project.getFile(savedRteConfigName);
				if (!iFile.exists() || iFile.getLocation() == null) {
					String msg = Messages.RteProjectUpdater_ErrorLoadinConfigFile + " '" + savedRteConfigName + "' " + //$NON-NLS-1$//$NON-NLS-2$
							Messages.RteProjectUpdater_ErrorConfigFileNotExist;
					Status status = new Status(IStatus.ERROR, CpPlugInUI.PLUGIN_ID, msg);
					throw new CoreException(status);
				}
				rteConfigName = savedRteConfigName;
			}
		}

		File file = iFile.getLocation().toFile();
		CpConfigParser confParser = new CpConfigParser();
		ICpItem root = confParser.parseFile(file.getAbsolutePath());
		IRteConfiguration rteConf = null;
		if (root instanceof ICpConfigurationInfo) {
			ICpConfigurationInfo info = (ICpConfigurationInfo) root;
			rteConf = new RteConfiguration();
			rteConf.setConfigurationInfo(info);
			rteProject.setRteConfiguration(rteConfigName, rteConf);

			// finally update project storage and rename file if needed
			if (!rteConfigName.equals(savedRteConfigName)) {
				rteProject.setRteConfigurationName(rteConfigName);
				bSaveProject = true;
			}
		} else {
			String msg = Messages.RteProjectUpdater_ErrorLoadinConfigFile + " '" + rteConfigName + "' " + //$NON-NLS-1$//$NON-NLS-2$
					Messages.RteProjectUpdater_ErrorParsingFailed;
			Status status = new Status(IStatus.ERROR, CpPlugInUI.PLUGIN_ID, msg);
			throw new CoreException(status);
		}

		return rteConf;
	}

	protected void removeResources() throws CoreException {
		IResource rteFolder = project.findMember(CmsisConstants.RTE);
		removeResources(rteFolder);
	}

	protected void removeResources(IResource res) throws CoreException {
		if (res == null) {
			return;
		}
		int type = res.getType();
		if (type == IResource.FILE) {
			IPath path = res.getProjectRelativePath();
			if (!rteProject.isFileUsed(path.toString())) {
				if (res.isLinked()) {
					res.delete(IResource.FORCE, monitor);
				} else if (bDeleteConfigFiles) {
					res.delete(IResource.FORCE | IResource.KEEP_HISTORY, monitor);
				} else {
					ProjectUtils.setExcludeFromBuild(project, path.toString(), true);
				}
			}
		} else if (res.getType() == IResource.FOLDER) {
			IFolder f = (IFolder) res;
			IResource[] members = f.members();
			for (IResource r : members) {
				removeResources(r);
			}
			f.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			if (!f.getName().equals(CmsisConstants.RTE) && f.members().length == 0) {
				f.delete(true, true, null);
			}
		}
	}

	protected void addResources() throws CoreException {
		IRteConfiguration rteConf = rteProject.getRteConfiguration();
		addResources(rteConf);
		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
	}

	protected void addResources(IRteConfiguration rteConf) throws CoreException {
		if (rteConf == null) {
			return;
		}

		Map<String, ICpFileInfo> fileMap = rteConf.getProjectFiles();
		for (Entry<String, ICpFileInfo> e : fileMap.entrySet()) {
			String projectRelativePath = e.getKey();
			ICpFileInfo fi = e.getValue();
			addFile(rteConf, projectRelativePath, fi);
		}
	}

	protected void addFile(IRteConfiguration rteConf, String dstFile, ICpFileInfo fi) throws CoreException {
		EFileRole role = fi.getRole();
		ICpFile f = fi.getFile();

		String srcFile = null;
		if (f != null) {
			srcFile = fi.getAbsolutePath(f.getName());
		}
		if (srcFile == null) {
			return;
		}

		if (role == EFileRole.CONFIG) {
			int index = -1;
			EFileCategory cat = fi.getCategory();
			if (cat.isHeader() || cat.isSource()) {
				String baseSrc = Utils.extractBaseFileName(srcFile);
				String baseDst = Utils.extractBaseFileName(dstFile);
				int len = baseSrc.length() + 1;
				if (baseDst.length() > len) {
					String instance = baseDst.substring(len);
					try {
						index = Integer.decode(instance);
					} catch (NumberFormatException e) {
						// do nothing, use -1
					}
				}
			}
			int bCopied = ProjectUtils.copyFile(project, srcFile, dstFile, index, monitor, false);
			if (bCopied == 1) {
				updateFileVersion(dstFile, fi.getVersion(), true);
			} else if (bCopied == -1) {
				String savedVersion = getFileVersion(dstFile);
				if (savedVersion != null) {
					fi.setVersion(savedVersion);
				}
			}
		} else if (role == EFileRole.COPY) {
			int bCopied = ProjectUtils.copyFile(project, srcFile, dstFile, -1, monitor, false);
			if (bCopied == 1) {
				updateFileVersion(dstFile, fi.getVersion(), true);
			} else if (bCopied == -1) {
				String savedVersion = getFileVersion(dstFile);
				if (savedVersion != null) {
					fi.setVersion(savedVersion);
				}
			}
		} else {
			srcFile = CpVariableResolver.insertCmsisRootVariable(srcFile);
			if (srcFile != null) {
				ProjectUtils.createLink(project, srcFile, dstFile, monitor);
			}
		}
		if (ProjectUtils.isExcludedFromBuild(project, dstFile)) {
			ProjectUtils.setExcludeFromBuild(project, dstFile, false);
		}
	}

	public void updateFileVersion(String projectRelativePath, String version, boolean bForce) {
		RteProjectStorage projectStorage = rteProject.getProjectStorage();
		if (bForce || projectStorage.getConfigFileVersion(projectRelativePath) == null) {
			projectStorage.setConfigFileVersion(projectRelativePath, version);
			bSaveProject = true;
		}
	}

	public String getFileVersion(String projectRelativePath) {
		RteProjectStorage projectStorage = rteProject.getProjectStorage();
		return projectStorage.getConfigFileVersion(projectRelativePath);
	}

	public void updateRteComponentsH() throws CoreException {
		// ensure resource exists
		try {
			IFile f = ProjectUtils.createFile(project, CmsisConstants.RTE_RTE_Components_h, monitor);
			IPath p = f.getLocation();
			p.toFile().setWritable(true);
			PrintWriter pw = new PrintWriter(p.toOSString());
			writeRteComponentsHhead(pw);
			writeRteComponentsHbody(pw);
			writeRteComponentsHtail(pw);
			pw.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	protected void writeRteComponentsHbody(PrintWriter pw) {
		IRteConfiguration rteConf = rteProject.getRteConfiguration();
		if (rteConf == null) {
			return;
		}

		// write #define CMSIS_device_header
		String deviceHeader = rteConf.getDeviceHeader();
		if (deviceHeader != null && !deviceHeader.isEmpty()) {
			String s = "#define " + CmsisConstants.CMSIS_device_header + " \"" + deviceHeader + "\""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			pw.println("/*"); //$NON-NLS-1$
			pw.println(" * Define the Device Header File:"); //$NON-NLS-1$
			pw.println("*/"); //$NON-NLS-1$
			pw.println(s);
			pw.println();
		}

		Collection<String> code = rteConf.getRteComponentsHCode();
		for (String s : code) {
			pw.println(s);
		}
	}

	protected void writeRteComponentsHhead(PrintWriter pw) {

		pw.println("/*"); //$NON-NLS-1$
		pw.println(" * Auto generated Run-Time-Environment Component Configuration File"); //$NON-NLS-1$
		pw.println(" *      *** Do not modify ! ***"); //$NON-NLS-1$
		pw.println(" *"); //$NON-NLS-1$
		pw.println(" * Project: " + rteProject.getName()); //$NON-NLS-1$
		pw.print(" * RTE configuration: "); //$NON-NLS-1$
		pw.println(rteProject.getRteConfigurationName());
		pw.println("*/"); //$NON-NLS-1$

		pw.println("#ifndef RTE_COMPONENTS_H"); //$NON-NLS-1$
		pw.println("#define RTE_COMPONENTS_H"); //$NON-NLS-1$
		pw.println();
	}

	protected void writeRteComponentsHtail(PrintWriter pw) {
		pw.println();
		pw.println("#endif /* RTE_COMPONENTS_H */"); //$NON-NLS-1$
	}

	protected void updateBuildSettings(boolean bForceUpdateToolchain) {
		RteProjectStorage ps = rteProject.getProjectStorage();
		if (ps == null) {
			return;
		}

		IRteToolChainAdapter adapter = ps.getToolChainAdapter();
		if (adapter == null) {
			return;
		}

		ICpEnvironmentProvider envProvider = CpPlugIn.getEnvironmentProvider();
		IAttributes deviceAttributes = ps.getDeviceAttributes();
		IRteConfiguration rteConfig = rteProject.getRteConfiguration();
		ICpConfigurationInfo configInfo = rteConfig.getConfigurationInfo();
		ICpDeviceInfo deviceInfo = rteConfig.getDeviceInfo();
		IBuildSettings buildSettings = rteConfig.getBuildSettings();

		boolean bInit = deviceInfo != null && !deviceInfo.attributes().matchCommonAttributes(deviceAttributes);
		if (bInit) {
			bSaveProject = true;
			ps.setDeviceInfo(deviceInfo);
			String linkerScriptFile = buildSettings.getSingleLinkerScriptFile();
			if (linkerScriptFile == null) {
				ILinkerScriptGenerator lsGen = adapter.getLinkerScriptGenerator();
				if (lsGen != null) {
					try {
						IMemorySettings memorySettings = RteConfiguration.createMemorySettings(deviceInfo);
						String script = lsGen.generate(memorySettings);
						if (script != null && !script.isEmpty()) {
							linkerScriptFile = getLinkerScriptFile(lsGen);
							writeLinkerScriptFile(linkerScriptFile, script);
							buildSettings.addStringListValue(IBuildSettings.RTE_LINKER_SCRIPT,
									CmsisConstants.PROJECT_LOCAL_PATH + linkerScriptFile);
						}
					} catch (CoreException e) {
						e.printStackTrace();
					}
				}
			}
		}

		IManagedBuildInfo buildInfo = ManagedBuildManager.getBuildInfo(project);
		String[] configNames = buildInfo.getConfigurationNames();
		for (String name : configNames) {
			IConfiguration config = ProjectUtils.getConfiguration(project, name);
			if (bInit || bForceUpdateToolchain) {
				envProvider.adjustInitialBuildSettings(buildSettings, configInfo);
				adapter.setInitialToolChainOptions(config, buildSettings);
			} else {
				envProvider.adjustBuildSettings(buildSettings, configInfo);
				adapter.setToolChainOptions(config, buildSettings);
			}

		}
		ManagedBuildManager.saveBuildInfo(project, true);
	}

	protected String getLinkerScriptFile(ILinkerScriptGenerator lsGen) {
		IRteConfiguration rteConfiguration = rteProject.getRteConfiguration();
		String deviceName = rteConfiguration.getDeviceInfo().getDeviceName();
		String fileName = Utils.wildCardsToX(deviceName) + "." + lsGen.getFileExtension(); //$NON-NLS-1$
		return fileName;
	}

	protected void writeLinkerScriptFile(String fileName, String script) {
		if (script == null || script.isEmpty()) {
			return;
		}

		try {
			IFile file = ProjectUtils.createFile(project, fileName, monitor);
			IPath loc = file.getLocation();
			File f = loc.toFile();
			if (f != null && f.exists()) {
				return; // destination file already exists
			}

			String osPath = loc.toOSString();
			PrintWriter pw = new PrintWriter(osPath);
			pw.write(script);
			pw.close();
		} catch (CoreException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Check if the pack manager contains the pack and it is already installed
	 * @param packAttributes pack attributes
	 * @return true if the pack installer has already installed the pack
	 */
	protected boolean hasInstalled(IAttributes packAttributes) {
		ICpPackCollection allPacks = CpPlugIn.getPackManager().getPacks();
		if (allPacks == null) {
			return false;
		}
		String packId = CpPack.constructPackId(packAttributes);
		ICpPack pack = allPacks.getPack(packId);
		if (pack == null) {
			return false;
		}
		return pack.getPackState() == PackState.INSTALLED
				|| pack.getPackState() == PackState.GENERATED;
	}

}
