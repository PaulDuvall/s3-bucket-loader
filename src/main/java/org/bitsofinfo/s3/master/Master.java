package org.bitsofinfo.s3.master;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.exec.CommandLine;
import org.apache.log4j.Logger;
import org.bitsofinfo.ec2.Ec2Util;
import org.bitsofinfo.s3.S3Util;
import org.bitsofinfo.s3.cmd.CmdResult;
import org.bitsofinfo.s3.cmd.CommandExecutor;
import org.bitsofinfo.s3.control.CCMode;
import org.bitsofinfo.s3.control.CCPayload;
import org.bitsofinfo.s3.control.CCPayloadHandler;
import org.bitsofinfo.s3.control.CCPayloadType;
import org.bitsofinfo.s3.control.ControlChannel;
import org.bitsofinfo.s3.toc.DirectoryCrawler;
import org.bitsofinfo.s3.toc.S3BucketObjectLister;
import org.bitsofinfo.s3.toc.SourceTOCGenerator;
import org.bitsofinfo.s3.toc.TOCManifestBasedGenerator;
import org.bitsofinfo.s3.toc.TOCPayload.MODE;
import org.bitsofinfo.s3.toc.TOCQueue;
import org.bitsofinfo.s3.toc.TocInfo;
import org.bitsofinfo.s3.util.CompressUtil;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.gson.Gson;


public class Master implements CCPayloadHandler, Runnable, TOCGenerationEventHandler {

	private static final Logger logger = Logger.getLogger(Master.class);

	
	private TOCQueue tocQueue = null;
	private ControlChannel controlChannel = null;
	private Properties props = null;
	
	private Collection<TocInfo> toc = null;
	
	private WorkerRegistry workerRegistry = new WorkerRegistry();
	private int totalExpectedWorkers = 0;
	
	private CCMode currentMode = null;
	
	private Date masterStartAt = null;
	private Date validationsStartAt = null;
	private Date validationsEndAt = null;
	private Date writesStartAt = null;
	private Date writesEndAt = null;
	
	private Gson gson = new Gson();
	private String awsAccessKey = null;
	private String awsSecretKey = null;
	
	private boolean workersEc2Managed = false;
	private AmazonEC2Client ec2Client = null;
	private List<Instance> ec2Instances = null;
	
	private SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
	private SimpleDateFormat ec2TagSimpleDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
	
	private Thread masterMonitor = null;
	private boolean masterMonitorRunning = true;
	
	private TOCGeneratorAndSender tocGeneratorAndSender = null;
	private int tocDispatchThreadsTotal = 4;
	
	private Ec2Util ec2util = null;
	private S3Util s3util = null;
	
	private boolean failfastOnWorkerCurrentSummaryError = false;
	
	private TOCQueueEmptier tocQueueEmptier = null;
	
	private int ec2MinutesToWait = 10;
	
	private String sourceEc2StartStopInstanceId = null;
	private boolean sourceEc2HostStopOnMasterShutdown = true;
	private String sourceEc2PostStartCmd = null;
	private String sourceEc2PreStopCmd = null;
	
	private boolean workerErrorReportsLogged = false;
	private String workerErrorReportsLogFile = null;

	private ShutdownInfo shutdownInfo = null;
	private List<String> masterLogFilesToUpload = null;
	
	private long lastStatsDumpAtMS = System.currentTimeMillis();
	private long dumpStatsEveryMS = 60000;
	
	private AmazonS3Client s3Client = null;
	
	private long autoShutdownAfterMS = -1;
	
	public Master(Properties props) {
		
		try {
			this.ec2util = new Ec2Util(); 
			this.s3util = new S3Util();
			
			this.props = props;

			String snsControlTopicName = props.getProperty("aws.sns.control.topic.name");
			String sqsQueueName = 		 props.getProperty("aws.sqs.queue.name");
			String userAccountPrincipalId =   props.getProperty("aws.account.principal.id");
			String userARN = 				  props.getProperty("aws.user.arn");
			this.totalExpectedWorkers = 	Integer.valueOf(props.getProperty("master.workers.total"));

			this.awsAccessKey = 		 props.getProperty("aws.access.key");
			this.awsSecretKey = 		 props.getProperty("aws.secret.key");
			
			this.tocDispatchThreadsTotal = Integer.valueOf(props.getProperty("master.tocqueue.dispatch.threads"));
			
			this.failfastOnWorkerCurrentSummaryError = Boolean.valueOf(props.getProperty("master.failfast.on.worker.current.summary.error"));
			logger.debug("failfastOnWorkerCurrentSummaryError=" + this.failfastOnWorkerCurrentSummaryError);
			
			if (props.getProperty("master.auto.shutdown.after.n.minutes") != null) {
				Integer minutes = Integer.valueOf(props.getProperty("master.auto.shutdown.after.n.minutes"));
				if (minutes > 0) {
					this.autoShutdownAfterMS = minutes  * (1000*60);
					logger.debug("master.auto.shutdown.after.n.minutes (milliseconds = " + this.autoShutdownAfterMS+")");
				}
			}
			
			/**
			 * For worker s3 log uploads on shutdown
			 */
			this.shutdownInfo = new ShutdownInfo();
			this.shutdownInfo.s3LogBucketName = props.getProperty("master.s3.log.bucket");
			this.shutdownInfo.s3LogBucketFolderRoot = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS").format(new Date());
			this.shutdownInfo.workerLogFilesToUpload = Arrays.asList(props.getProperty("master.s3.log.worker.files").split(","));
			this.masterLogFilesToUpload = Arrays.asList(props.getProperty("master.s3.log.master.files").split(","));
			
			// connect to ec2 & s3
			this.ec2Client = new AmazonEC2Client(new BasicAWSCredentials(this.awsAccessKey, this.awsSecretKey));
			this.s3Client = new AmazonS3Client(new BasicAWSCredentials(this.awsAccessKey, this.awsSecretKey));
			
			if (props.getProperty("master.workers.ec2.minutes.to.wait.for.worker.init") != null) {
				ec2MinutesToWait = Integer.valueOf(props.getProperty("master.workers.ec2.minutes.to.wait.for.worker.init"));
			}
			
			if (props.getProperty("master.source.host.ec2.instanceId") != null) {
				this.sourceEc2StartStopInstanceId = props.getProperty("master.source.host.ec2.instanceId");
				this.sourceEc2HostStopOnMasterShutdown = Boolean.valueOf(props.getProperty("master.source.host.ec2.stopOnMasterShutdown"));
				this.sourceEc2PostStartCmd = props.getProperty("master.source.host.post.start.cmd");
				this.sourceEc2PreStopCmd = props.getProperty("master.source.host.pre.stop.cmd");
			}
			
			this.workerErrorReportsLogFile = props.getProperty("master.workers.error.report.logfile");

			tocQueue = 		 new TOCQueue(false, awsAccessKey, awsSecretKey, sqsQueueName, null);
			controlChannel = new ControlChannel(true, awsAccessKey, awsSecretKey, snsControlTopicName, userAccountPrincipalId, userARN, this);
			
			totalExpectedWorkers = Integer.valueOf(props.getProperty("master.workers.total"));
			
			workersEc2Managed = Boolean.valueOf(props.getProperty("master.workers.ec2.managed"));

		} catch(Exception e) {
			logger.error("Master() unexpected error: " + e.getMessage(),e);
			try {
				destroy();
			} catch(Exception ignore){}
		}
	}
	
	// called by TOCGeneratorAndSender when TOC generation is completed
	public void tocGenerationComplete(Collection<TocInfo> generatedTOC) {
		this.toc = generatedTOC;
	}
	
	public void tocGenerationError(String msg, Exception e) {
		logger.error("TOCGeneration threw an error, destroying myself....: " + msg, e);
		try {
			this.destroy();
		} catch(Exception e2) {
			// ignore;
		}
	}
	
	private void spawnEC2() throws Exception {
		
		if (!workersEc2Managed) {
			return;
		}

		this.ec2Instances = ec2util.launchEc2Instances(ec2Client, props);
		
		// collect some info
		StringBuffer sb = new StringBuffer("EC2 instance request created, reservation details:\n");
		for (Instance instance : ec2Instances) {
			sb.append("\tid:" + instance.getInstanceId() + "\tip:" + instance.getPrivateIpAddress() + "\thostname:"+instance.getPrivateDnsName()+"\n");
		}
		logger.info(sb.toString()+"\n");
		
		Thread.currentThread().sleep(60000);
		
		// tag instances
		CreateTagsRequest tagRequest = new CreateTagsRequest();
		String dateStr = ec2TagSimpleDateFormat.format(new Date());
		
		for (Instance instance : this.ec2Instances) {
			List<String> amiIds = new ArrayList<String>();
			List<Tag> tags = new ArrayList<Tag>();
			
			amiIds.add(instance.getInstanceId());
			tags.add(new Tag("Name","s3BktLdr-wkr-"+dateStr));
			
			tagRequest.setResources(amiIds);
			tagRequest.setTags(tags);
			
			ec2Client.createTags(tagRequest);
		}

		

	}
	
	
	public void start() throws Exception {

		
		// seed start
		this.masterStartAt = new Date();
		
		/**
		 * START the source for our TOC... if specified
		 */
		if (this.sourceEc2StartStopInstanceId != null) {
			
			// already running?
			InstanceStatus alreadyRunning = ec2util.getInstanceStatus(this.ec2Client, this.sourceEc2StartStopInstanceId);
			if (alreadyRunning == null || !alreadyRunning.getInstanceState().getName().equalsIgnoreCase("running")) {
				this.ec2util.startInstance(this.ec2Client, this.sourceEc2StartStopInstanceId);
			}
			
			// check status
			boolean sourceEc2InstanceStarted = false;
			while(!sourceEc2InstanceStarted) {
				InstanceStatus status = ec2util.getInstanceStatus(this.ec2Client, this.sourceEc2StartStopInstanceId);
				if (status == null || !status.getInstanceState().getName().equalsIgnoreCase("running")) {
					sourceEc2InstanceStarted = false;
					logger.debug("Still waiting for source host instance ["+this.sourceEc2StartStopInstanceId+"] to be running....");
					Thread.currentThread().sleep(2000);
				} else {
					logger.debug("Up and RUNNING! source host instance ["+this.sourceEc2StartStopInstanceId+"]");
					sourceEc2InstanceStarted = true;
					break;
				}
			}

			
			if (this.sourceEc2PostStartCmd != null) {
				
				Thread.currentThread().sleep(30000);
				
				// run the 'master.source.host.post.start.cmd' command
				CmdResult postStartResult = new CommandExecutor().execute(CommandLine.parse(this.sourceEc2PostStartCmd), 3);
				if (postStartResult.getExitCode() > 0) {
					throw new Exception("Source host post start cmd failed: " + this.sourceEc2PostStartCmd);
				}
			}
			
		}

		
		if (props.getProperty("tocGenerator.source.dir") != null && !new File(props.getProperty("tocGenerator.source.dir")).exists()) {
			throw new Exception("'tocGenerator.source.dir' does not exist! " + props.getProperty("tocGenerator.source.dir"));
		}
		
		// initialize workers 
		this.currentMode = CCMode.INITIALIZED;
		this.controlChannel.send(true, CCPayloadType.MASTER_CURRENT_MODE, this.currentMode);
		
		// spawn ec2 cluster if specified
		spawnEC2();
		
		// start our own monitor
		this.masterMonitor = new Thread(this);
		this.masterMonitor.start();
		
		// @see handlePayload(CCPayload) for what happens next as workers come online
		
	}

	
	public void destroy() throws Exception {
		
		try {
			dumpWorkerRegistryStats();
		} catch(Exception e) {}
		
		try {
			dumpRuntimeInfo();
		} catch(Exception e) {}
		
		try {
			// just in case
			this.controlChannel.send(true, CCPayloadType.CMD_WORKER_SHUTDOWN, gson.toJson(this.shutdownInfo));
		} catch(Exception ignore){}
		
		
		// upload logs
		try {
			this.s3util.uploadToS3(this.s3Client, 
								   this.shutdownInfo.s3LogBucketName, 
								   this.shutdownInfo.s3LogBucketFolderRoot, 
								   "MASTER", 
								   this.masterLogFilesToUpload);
		} catch(Exception ignore){}
		
		try {
			tocGeneratorAndSender.destroy();
		} catch(Exception ignore){}
		
		try {
			tocQueueEmptier.destroy();
		} catch(Exception ignore){}

		Thread.currentThread().sleep(10000);

		try { 
			logger.debug("Calling TOCQueue.destroy()");
			tocQueue.destroy();
		} catch(Exception ignore){}
		
		try { 
			logger.debug("Calling ControlChannel.destroy()");
			controlChannel.destroy();
		} catch(Exception ignore){}
		
		
		this.masterMonitorRunning = false;
		
		Thread.currentThread().sleep(10000);

		if (ec2Instances != null) {
			try {
				for (Instance ec2 : this.ec2Instances) {
					ec2util.terminateEc2Instance(ec2Client, ec2.getInstanceId());
				}
			
			} catch(Exception e){
				logger.error("Error terminating instances: " + e.getMessage(),e);
			}
		}

		
		try {
			if (this.sourceEc2PreStopCmd != null) {
				// run the 'master.source.host.pre.stop.cmd' command
				CmdResult postStartResult = new CommandExecutor().execute(CommandLine.parse(this.sourceEc2PreStopCmd), 3);
				if (postStartResult.getExitCode() > 0) {
					logger.error("ERROR running: " +sourceEc2PreStopCmd); 
				}
			}
		} catch(Exception e) {
			logger.error("ERROR running: " +sourceEc2PreStopCmd + " " + e.getMessage(),e);
		}
		
		
		
		if (this.sourceEc2HostStopOnMasterShutdown) {
			try {
				ec2util.stopInstance(ec2Client, sourceEc2StartStopInstanceId);
			} catch(Exception ignore){}
		}

	}

	
	private SourceTOCGenerator getSourceTOCGenerator(Properties props) throws Exception {
		SourceTOCGenerator tocGenerator = (SourceTOCGenerator)Class.forName(props.getProperty("tocGenerator.class").toString()).newInstance();
		configureTocGenerator(tocGenerator,props);
		return tocGenerator;
	}
	
	private void configureTocGenerator(SourceTOCGenerator generator, Properties props) throws Exception {
		
		// dir crawler
		if (generator instanceof DirectoryCrawler) {
			((DirectoryCrawler)generator).setRootDir(new File(props.getProperty("tocGenerator.source.dir").toString()));
			
			String lastModFilterStr = props.getProperty("tocGenerator.lastModifiedAtGreaterThanFilter");
			
			if (lastModFilterStr != null) {
				((DirectoryCrawler)generator).setLastModifiedAtGreaterThanFilter(
						new SimpleDateFormat("yyyy-MM-dd").parse(lastModFilterStr).getTime());
				
				logger.debug("SourceTOCGenerator DirectoryCrawler: will filter files where last modified at is > " + lastModFilterStr);
			}
			
		}
		
		// manifest reader
		if (generator instanceof TOCManifestBasedGenerator) {
			
			((TOCManifestBasedGenerator)generator).setRootDir(new File(props.getProperty("tocGenerator.source.dir").toString()));
			((TOCManifestBasedGenerator)generator).setManifestFile(new File(props.getProperty("tocGenerator.toc.manifest.file").toString()));
			
		}
		
		// s3 bucket reader
		if (generator instanceof S3BucketObjectLister) {
			((S3BucketObjectLister)generator).setS3BucketName(props.getProperty("tocGenerator.source.s3.bucketName").toString());
			((S3BucketObjectLister)generator).setS3Client(this.s3Client);
			
		}
		
		logger.debug("SourceTOCGenerator = " + generator.getClass().getName());
	}
	
	private String getTocSizeInfo() {
		return (this.toc != null ? String.valueOf(this.toc.size()) : "[TBD; generation in progress] ");
	}

	private void dumpWorkerRegistryStats() {
		
		StringBuffer sb = new StringBuffer("\n");
		
		sb.append("Total TOC size: " + (this.toc != null ? this.toc.size() : 0) + "\n");
		sb.append("Total written: " + workerRegistry.getTotalWritten() + "\n");
		sb.append("Total write failures: " + workerRegistry.getTotalWriteFailures() + "\n");
		sb.append("Total validated: " + workerRegistry.getTotalValidated() + "\n");
		sb.append("Total validate failures: " + workerRegistry.getTotalValidateFailures() + "\n");
		sb.append("Total write monitor errors: " + workerRegistry.getTotalWriteMonitorErrors() + "\n");
		sb.append("Total post-write local validate errors: " + workerRegistry.getTotalPostWriteLocalValidateErrors() + "\n");
		sb.append("\n\n");
		
		logger.info(sb.toString());
	}

	public void handlePayload(CCPayload payload) {
		
		// ignore messages from ourself
		if (payload.fromMaster) {
			return;
		}
		
		logger.trace("handlePayload() received CCPayload: fromMaster: " + payload.fromMaster + 
				" sourceHostId:" + payload.sourceHostId + 
				" sourceHostIp:" + payload.sourceHostIP + 
				" onlyForHost:" + payload.onlyForHostIdOrIP + 
				" type:" + payload.type +
				" value:" + payload.value);
		
		logger.debug("received CCPayload: FROM:"+payload.sourceHostIP + " type:"+payload.type + " val:"+payload.value);
		
		// if from worker....
		if (!payload.fromMaster) {
			workerRegistry.registerWorkerPayload(payload);
		}
		
		// check for all workers in INITIALIZED mode
		if (currentMode == CCMode.INITIALIZED && 
			workerRegistry.size() == this.totalExpectedWorkers &&
			workerRegistry.allWorkersCurrentModeIs(CCMode.INITIALIZED)) {
			
			try {
				logger.info("All workers report INITIALIZED.. total: " + workerRegistry.size() + " Proceeding to build TOC....");

				// execute WRITE mode
				this.currentMode = CCMode.WRITE;
				this.writesStartAt = new Date();
				
				// switch the system to WRITE mode so workers can immediately start polling
				logger.info("Switching to WRITE mode....");
				controlChannel.send(true, CCPayloadType.MASTER_CURRENT_MODE, this.currentMode);
				
				
				// fire up a TOCGeneratorAndSender (separate thread) to
				// generate the TOC and dispatch it out to the TOCQueue
				this.tocGeneratorAndSender = new TOCGeneratorAndSender(MODE.WRITE, 
																		this, 
																		this.tocQueue, 
																		this.tocDispatchThreadsTotal, 
																		getSourceTOCGenerator(this.props));
				this.tocGeneratorAndSender.generateAndSendTOC();

			} catch(Exception e) {
				logger.error("handlePayload() error handling WRITE mode completion and" +
						" triggerring VALIDATE mode..." + e.getMessage(),e);
			}
			
		// initialized, but still waiting....
		} else if (currentMode == CCMode.INITIALIZED && workerRegistry.size() != this.totalExpectedWorkers) {
			logger.info("Total workers registered = " + workerRegistry.size() + " expected:"+this.totalExpectedWorkers);
		}
		
		
		// Check for WRITE complete
		if (currentMode == CCMode.WRITE && workerRegistry.allWorkerWritesAreComplete()) {
			try {
			
				// dump runtime
				this.writesEndAt = new Date();
				logger.info("WORKER WRITES COMPLETE:  START["+simpleDateFormat.format(writesStartAt)+"] --> END["+simpleDateFormat.format(writesEndAt)+"]");
				
				dumpWorkerRegistryStats();
				
				// were there any errors?
				if (workerRegistry.anyWorkerWritesContainErrors()) {
	
					logger.info("One or more workers report WRITE mode completed.. but with some ERRORS. totalWritten: " + 
							workerRegistry.getTotalWritten() + " total TOC sent: " + getTocSizeInfo()+ 
							"(expected size). I am now triggering REPORT_ERRORS mode across all workers");
					
					this.currentMode = CCMode.REPORT_ERRORS;
					
					// switch the system to REPORT_ERRORS mode
					controlChannel.send(true, CCPayloadType.MASTER_CURRENT_MODE, this.currentMode);
					
					
				// no errors move onto next phase....(validate)
				} else {
					logger.info("All workers report WRITE mode completed.. totalWritten: " + 
							workerRegistry.getTotalWritten() + " total TOC sent: " + getTocSizeInfo() + 
							"(expected size). I am now triggering VALIDATE mode across all workers");

					// switch the system to VALIDATE mode so workers can immediately start polling
					logger.info("Switching to VALIDATE mode....");
					this.validationsStartAt = new Date();
					this.currentMode = CCMode.VALIDATE;
					controlChannel.send(true, CCPayloadType.MASTER_CURRENT_MODE, this.currentMode);
					
					// fire up a TOCGenera)torAndSender (separate thread) to
					// generate the TOC and dispatch it out to the TOCQueue
					this.tocGeneratorAndSender = new TOCGeneratorAndSender(MODE.VALIDATE, 
																			this, 
																			this.tocQueue, 
																			this.tocDispatchThreadsTotal, 
																			this.toc);
					this.tocGeneratorAndSender.generateAndSendTOC();
				}
				
			} catch(Exception e) {
				logger.error("handlePayload() error handling WRITE mode completion..." + e.getMessage(),e);
			}
			
		// WRITE partially complete? dump the workers we are waiting on....
		} else if (currentMode == CCMode.WRITE && workerRegistry.anyWorkerWritesAreComplete()) {
			StringBuffer waitingSB = new StringBuffer("\nWe are awaiting WRITE reports from the following workers:\n");
			for (String awaiting : workerRegistry.getWorkersAwaitingWriteReport()) {
				waitingSB.append(awaiting+"\n");
			}
			logger.info(waitingSB.toString()+"\n");
			
			
		// WRITE partially complete, we have some current write summaries
		} else if (currentMode == CCMode.WRITE && workerRegistry.anyWorkerCurrentWriteSummariesReceived()) {
			
			try {
				int totalWrittenSoFar = workerRegistry.getTotalWritten();
				logger.info("Total written across cluster so far: " + totalWrittenSoFar + " tocSize:" + getTocSizeInfo());
			
				// if any current summaries contain errors....and we are in fail fast mode...
				if (this.failfastOnWorkerCurrentSummaryError && workerRegistry.anyWorkerCurrentSummaryWritesContainErrors()) {
					
					logger.info("One or more workers report WRITE mode current summary with ERRORS. " +
							"failfastOnWorkerCurrentSummaryError=true, so I am now triggering " +
							"REPORT_ERRORS mode across all workers and stopping WRITE mode.");
					
					this.currentMode = CCMode.REPORT_ERRORS;
					
					// switch the system to REPORT_ERRORS mode
					controlChannel.send(true, CCPayloadType.MASTER_CURRENT_MODE, this.currentMode);
					
					// purge the TOCQueue
					this.purgeTOCQueueContents();

				}
				
			} catch(Exception e) {
				logger.error("handlePayload() error in WRITE reaction to current" +
						" summaries received with ERRORS over control channel " + e.getMessage(),e);
			}
				
		}	
			
			
			
		// Check for WRITE complete
		if (currentMode == CCMode.VALIDATE && workerRegistry.allWorkerValidatesAreComplete()) {
			
			try {
				
				// dump runtime
				this.validationsEndAt = new Date();
				logger.info("WORKER VALIDATIONS COMPLETE:  START["+simpleDateFormat.format(validationsStartAt)+"] --> END["+simpleDateFormat.format(validationsEndAt)+"]");
				
				dumpWorkerRegistryStats();
				
				// were there any errors?
				if (workerRegistry.anyWorkerValidationsContainErrors()) {
	
					logger.info("One or more workers report VALIDATE mode completed.. but with some ERRORS. totalValidated: " + 
							workerRegistry.getTotalValidated() + " total TOC sent: " + getTocSizeInfo() + 
							"(expected size). I am now triggering REPORT_ERRORS mode across all workers");
					
					this.currentMode = CCMode.REPORT_ERRORS;
					
					// switch the system to REPORT_ERRORS mode
					controlChannel.send(true, CCPayloadType.MASTER_CURRENT_MODE, this.currentMode);
					
					
				// no errors, go to shutdown!
				} else {
					logger.info("All workers report VALIDATE mode completed.. totalValidated: " + 
							workerRegistry.getTotalValidated() + " total TOC sent: " + getTocSizeInfo() + 
							" I am now issueing CMD_WORKER_SHUTDOWN");
					
					// dump runtime
					dumpRuntimeInfo();
					
					this.controlChannel.send(true, CCPayloadType.CMD_WORKER_SHUTDOWN, gson.toJson(this.shutdownInfo));
				}

			} catch(Exception e) {
				logger.error("handlePayload() error sending CMD_WORKER_SHUTDOWN over control channel " + e.getMessage(),e);
			}
			
			
			
		// VALIDATE partially complete? dump the workers we are waiting on....
		} else if (currentMode == CCMode.VALIDATE && workerRegistry.anyWorkerValidatesAreComplete()) {
			
			StringBuffer waitingSB = new StringBuffer("\nWe are awaiting VALIDATE reports from the following workers:\n");
			for (String awaiting : workerRegistry.getWorkersAwaitingValidationReport()) {
				waitingSB.append(awaiting+"\n");
			}
			logger.info(waitingSB.toString()+"\n");
			
			
			
		// VALIDATE partially complete, we have some current write summaries
		} else if (currentMode == CCMode.VALIDATE && workerRegistry.anyWorkerCurrentValidationSummariesReceived()) {
			
			try {
				int totalValidatedSoFar = workerRegistry.getTotalValidated();
				logger.info("Total validated across cluster so far: " + totalValidatedSoFar + " tocSize:" + getTocSizeInfo());
			
				// if any current summaries contain errors....and we are in fail fast mode...
				if (this.failfastOnWorkerCurrentSummaryError && workerRegistry.anyWorkerCurrentValidationsContainErrors()) {
					
					logger.info("One or more workers report VALIDATE mode current summary with ERRORS. " +
							"failfastOnWorkerCurrentSummaryError=true, so I am now triggering " +
							"REPORT_ERRORS mode across all workers and stopping VALIDATE mode.");

					this.currentMode = CCMode.REPORT_ERRORS;
					
					// switch the system to REPORT_ERRORS mode
					controlChannel.send(true, CCPayloadType.MASTER_CURRENT_MODE, this.currentMode);
					
					// purge the TOCQueue
					this.purgeTOCQueueContents();
					
				}
			} catch(Exception e) {
				logger.error("handlePayload() error in WRITE reaction to current" +
						" summaries received with ERRORS over control channel " + e.getMessage(),e);
			}
				
		}	
		
		
		// Check for REPORT_ERRORS complete
		if (currentMode == CCMode.REPORT_ERRORS && workerRegistry.allWorkerErrorReportsAreIn() && !workerErrorReportsLogged) {
			
			// we only want to do this once
			synchronized(this) {
				
				if (workerErrorReportsLogged) {
					return;
				}
			
				// so we only do this once
				this.workerErrorReportsLogged = true; 
				
				try {
					
					logger.info("All workers report REPORT_ERRORS mode completed.. dumping details and triggering system CMD_WORKER_SHUTDOWN");
					
					// log em
					logWorkerErrorReports();
					
					// dump runtime
					dumpRuntimeInfo();
					
					// final stats
					dumpWorkerRegistryStats();
					
					// send out the shutdown..
					this.controlChannel.send(true, CCPayloadType.CMD_WORKER_SHUTDOWN, gson.toJson(this.shutdownInfo));
					
					if (this.autoShutdownAfterMS > 0) {
						logger.debug("System will auto-shutdown in: " + autoShutdownAfterMS + "ms " + (autoShutdownAfterMS/60000) + " (minutes)");
						Thread.currentThread().sleep(this.autoShutdownAfterMS);
						System.exit(0);
					}
	
				} catch(Exception e) {
					logger.error("handlePayload() error sending CMD_WORKER_SHUTDOWN over control channel: " + e.getMessage(),e);
				}
			}
			
		// REPORT_ERRORS partially complete? dump the workers we are waiting on....
		} else if (currentMode == CCMode.REPORT_ERRORS && workerRegistry.anyWorkerErrorReportsAreReceived()) {
			StringBuffer waitingSB = new StringBuffer("\nWe are awaiting REPORT_ERRORS reports from the following workers:\n");
			for (String awaiting : workerRegistry.getWorkersAwaitingErrorReport()) {
				waitingSB.append(awaiting+"\n");
			}
			logger.info(waitingSB.toString()+"\n");
		}
		
		
		try {
			long now = System.currentTimeMillis();
			if ((now - lastStatsDumpAtMS) > this.dumpStatsEveryMS) {
				dumpWorkerRegistryStats();
				this.lastStatsDumpAtMS = System.currentTimeMillis();
			}
		} catch(Exception e) {
			logger.error("Error calling dumpWorkerRegistryStats() " + e.getMessage(),e);
		}
		
		
	}

	private void logWorkerErrorReports() throws Exception {
		
		try {
			// log file
			Writer logWriter = null;
			if (this.workerErrorReportsLogFile != null) {
				try {
					File targetFile = new File(this.workerErrorReportsLogFile);
					if (!targetFile.exists()) {
						targetFile.createNewFile();
					}
					logWriter = new BufferedWriter(new FileWriter(targetFile));
					logger.info("Writing JSON error reports to: " + this.workerErrorReportsLogFile);
				} catch(Exception e) {
					logger.error("Could NOT create worker error report log file! " + workerErrorReportsLogFile);
				}
			}
			
			if (logWriter != null) {
				try {
					logWriter.write("{ \"errorReports\" : [");
				} catch(Exception e) {
					logger.error("Error writing to : " + this.workerErrorReportsLogFile + " " + e.getMessage());
				}
			}
			
			StringBuffer sb = new StringBuffer();
			int count = 0;
			for (String worker : workerRegistry.getWorkerHostnames()) {
				
				sb.append("\n--------------------------------------------------\n");
				sb.append("WORKER: "+worker+"\n");
				sb.append("--------------------------------------------------\n");
				
				WorkerInfo winfo = workerRegistry.getWorkerInfo(worker);
				String reportPayloadValue = (String)winfo.getPayloadValue(CCPayloadType.WORKER_ERROR_REPORT_DETAILS);
				
				// decompress...
				String errorReportJson = new String(CompressUtil.decompressAndB64DecodeUTF8Bytes(reportPayloadValue.getBytes()));
				
				if (logWriter != null) {
					try {
						if (count > 0) {
							logWriter.write(" , ");
						}
						logWriter.write(errorReportJson);
					} catch(Exception e) {
						logger.error("Error writing to : " + this.workerErrorReportsLogFile + " " + e.getMessage());
					}
				}
				
				sb.append("\n"+errorReportJson+"\n");
				
				sb.append("--------------------------------------------------\n");

				if (logWriter != null) {
					try {
						logWriter.flush();
					} catch(Exception e) {
						logger.error("Error writing to : " + this.workerErrorReportsLogFile + " " + e.getMessage());
					}
				}
				
				count++;
			}
			
			if (logWriter != null) {
				try {
					logWriter.write("] }");
					logWriter.close();
					logger.info("JSON error reports flushed to log file: " + this.workerErrorReportsLogFile);
				} catch(Exception e) {
					logger.error("Error writing to : " + this.workerErrorReportsLogFile + " " + e.getMessage());
				}
			}
			
			// flush out
			logger.error(sb.toString());
			
			
		} catch(Exception e) {
			logger.error("logWorkerErrorReports() unexpected error: " + e.getMessage(),e);
		}
	}
	
	private void dumpRuntimeInfo() {
		// dump runtime
		logger.info("MASTER TOTAL RUNTIME:  START["+simpleDateFormat.format(masterStartAt)+"] --> END["+simpleDateFormat.format(new Date())+"]");
		
		if (writesEndAt != null) {
			logger.info("WRITE RUNTIME:  START["+simpleDateFormat.format(writesStartAt)+"] --> END["+simpleDateFormat.format(writesEndAt)+"]");
		}
		
		if (validationsEndAt != null) {
			logger.info("VALIDATION RUNTIME:  START["+simpleDateFormat.format(validationsStartAt)+"] --> END["+simpleDateFormat.format(validationsEndAt)+"]");
		}
	}
	
	public void run() {
		while(masterMonitorRunning) {
			try {
				if (this.currentMode == CCMode.INITIALIZED && workersEc2Managed) {
					
					// dump status and get impaired instances
					List<String> impairedInstanceIds = ec2util.dumpEc2InstanceStatus(ec2Client,ec2Instances);
					
					// terminate impaired instanceIds 
					for (String instanceId : impairedInstanceIds) {
						logger.warn("Terminating impaired EC2 instance that won't likely come up: " + instanceId);
						ec2util.terminateEc2Instance(ec2Client, instanceId);
						this.totalExpectedWorkers--;
					}
					
					// collect those that have yet to register
					List<String> ec2InstanceIdsNoInitializedWorker = new ArrayList<String>();
					Map<String,String> instanceId2IP = ec2util.getPrivateIPs(ec2Instances);
					for (String ec2InstanceId : instanceId2IP.keySet()) {
						String ec2IP = instanceId2IP.get(ec2InstanceId);
						if (workerRegistry.getWorkerByIP(ec2IP) == null) {
							logger.warn("EC2 node: " + ec2IP + " has yet to register its worker...");
							ec2InstanceIdsNoInitializedWorker.add(ec2InstanceId);
						}
					}

					// if its been more than ec2MinutesToWait minutes since we started and we STILL
					// have ec2 instances that have yet to report their worker, kill THEM!
					// and decrement expected workers so the next INITIALIZE resend by workers
					// will get us moving forward.
					if (System.currentTimeMillis() - this.masterStartAt.getTime() > (60000*ec2MinutesToWait)) {
						logger.debug("Its been more than "+ec2MinutesToWait+" minutes since we've started and are short workers... terminating them");
						for (String instanceId2term : ec2InstanceIdsNoInitializedWorker) {
							
							// issue shutdown to them only first
							
							String ip = instanceId2IP.get(instanceId2term);
							logger.debug("Sending CMD_WORKER_SHUTDOWN targeted ONLY for worker ip: " + ip);
							this.controlChannel.send(true, CCPayloadType.CMD_WORKER_SHUTDOWN, ip, gson.toJson(this.shutdownInfo));
							
							Thread.currentThread().sleep(10000);
							
							ec2util.terminateEc2Instance(ec2Client,instanceId2term);
							this.totalExpectedWorkers--;
							logger.debug("Total expected workers is now: " + this.totalExpectedWorkers);
						}
					}
					
				}
				
				Thread.currentThread().sleep(30000);
			} catch(Exception e) {
				logger.error("Unexpected error: " + e.getMessage(),e);
			}
		}
	}
	
	private void purgeTOCQueueContents() {
		try {
			// Disabled, can't seem to get this to work.
			// Constantly get There should be at least one DeleteMessageBatchRequestEntry in the request.
			//tocQueueEmptier = new TOCQueueEmptier(this.tocQueue, this.tocDispatchThreadsTotal);
			//tocQueueEmptier.start();

		} catch(Exception e) {
			logger.error("Unexpected error purging TOC contents: "+e.getMessage(),e);
		}
	}
	
}
