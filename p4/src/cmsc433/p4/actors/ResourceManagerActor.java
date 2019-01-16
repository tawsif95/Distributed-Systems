package cmsc433.p4.actors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import cmsc433.p4.enums.AccessRequestDenialReason;
import cmsc433.p4.enums.AccessRequestType;
import cmsc433.p4.enums.AccessType;
import cmsc433.p4.enums.ManagementRequestDenialReason;
import cmsc433.p4.enums.ManagementRequestType;
import cmsc433.p4.enums.ResourceStatus;
import cmsc433.p4.messages.AccessReleaseMsg;
import cmsc433.p4.messages.AccessRequestDeniedMsg;
import cmsc433.p4.messages.AccessRequestGrantedMsg;
import cmsc433.p4.messages.AccessRequestMsg;
import cmsc433.p4.messages.AddInitialLocalResourcesRequestMsg;
import cmsc433.p4.messages.AddInitialLocalResourcesResponseMsg;
import cmsc433.p4.messages.AddLocalUsersRequestMsg;
import cmsc433.p4.messages.AddLocalUsersResponseMsg;
import cmsc433.p4.messages.AddRemoteManagersRequestMsg;
import cmsc433.p4.messages.AddRemoteManagersResponseMsg;
import cmsc433.p4.messages.LogMsg;
import cmsc433.p4.messages.ManagementRequestDeniedMsg;
import cmsc433.p4.messages.ManagementRequestGrantedMsg;
import cmsc433.p4.messages.ManagementRequestMsg;
import cmsc433.p4.messages.WhoHasResourceRequestMsg;
import cmsc433.p4.messages.WhoHasResourceResponseMsg;
import cmsc433.p4.util.AccessRelease;
import cmsc433.p4.util.AccessRequest;
import cmsc433.p4.util.ManagementRequest;
import cmsc433.p4.util.Resource;


/*
 * 
 * TEST SUBMISSION
 * 
 * */
public class ResourceManagerActor extends UntypedActor {

	private ActorRef logger;					// Actor to send logging messages to

	/**
	 * Props structure-generator for this class.
	 * @return  Props structure
	 */
	static Props props (ActorRef logger) {
		return Props.create(ResourceManagerActor.class, logger);
	}

	/**
	 * Factory method for creating resource managers
	 * @param logger			Actor to send logging messages to
	 * @param system			Actor system in which manager will execute
	 * @return					Reference to new manager
	 */
	public static ActorRef makeResourceManager (ActorRef logger, ActorSystem system) {
		ActorRef newManager = system.actorOf(props(logger));
		return newManager;
	}

	/**
	 * Sends a message to the Logger Actor
	 * @param msg The message to be sent to the logger
	 */
	public void log (LogMsg msg) {
		logger.tell(msg, getSelf());
	}

	/**
	 * Constructor
	 * 
	 * @param logger			Actor to send logging messages to
	 */
	private ResourceManagerActor(ActorRef logger) {
		super();
		this.logger = logger;
	}

	// You may want to add data structures for managing local resources and users, storing
	// remote managers, etc.
	//
	// REMEMBER:  YOU ARE NOT ALLOWED TO CREATE MUTABLE DATA STRUCTURES THAT ARE SHARED BY
	// MULTIPLE ACTORS!

	private Set<ActorRef> localUsers = new HashSet<ActorRef>();
	private Set<ActorRef> remoteManagers = new HashSet<ActorRef>();
	private Map<ActorRef, Set<String>> discoverManagers = new HashMap<ActorRef, Set<String>>();

	private Map<String, Resource> localResources = new HashMap<String, Resource>();

	private Map<String, Queue<AccessRequestMsg>> resourceToBlockingRequests = 
			new HashMap<String, Queue<AccessRequestMsg>>();

	private Map<String, List<ActorRef>> resourceToReads = new HashMap<String, List<ActorRef>>();
	private Map<String, List<ActorRef>> resourceToWrites = new HashMap<String, List<ActorRef>>();

	private Map<String, ManagementRequestMsg> toBeDisabledResources = new HashMap<String, ManagementRequestMsg>();

	private Map<ActorRef, Set<String>> remoteResource = new HashMap<ActorRef, Set<String>>();
	private Map<Object, Set<ActorRef>> msgToRemoteManagers = new HashMap<Object, Set<ActorRef>>();
	/* (non-Javadoc)
	 * 
	 * You must provide an implementation of the onReceive() method below.
	 * 
	 * @see akka.actor.UntypedActor#onReceive(java.lang.Object)
	 */
	@Override
	public void onReceive(Object msg) throws Exception {	

		if (msg instanceof AccessRequestMsg) {
			AccessRequestMsg payload = (AccessRequestMsg) msg;
			ActorRef replyTo = payload.getReplyTo();
			AccessRequest request = payload.getAccessRequest();

			String resourceName = request.getResourceName();
			AccessRequestType accessType = request.getType();

			logger.tell(LogMsg.makeAccessRequestReceivedLogMsg(replyTo, getSelf(), request), getSender());


			if(localResources.containsKey(resourceName)){
				if (localResources.get(resourceName).getStatus() == ResourceStatus.DISABLED 
						|| toBeDisabledResources.containsKey(resourceName)) {
					request_denied(payload, replyTo, request);
				}
				if(accessType == AccessRequestType.CONCURRENT_READ_BLOCKING
						|| accessType == AccessRequestType.EXCLUSIVE_WRITE_BLOCKING){
					request_blocking(resourceName, payload, request, replyTo);

				} else if (resourceToBlockingRequests.get(resourceName) == null
						|| resourceToBlockingRequests.get(resourceName).isEmpty()) {

					if (accessType == AccessRequestType.CONCURRENT_READ_NONBLOCKING) {

						if (canHaveConcurrentReadAccess(resourceName, replyTo)) {
							giveConcurrentReadAccess(resourceName, replyTo, request);
						} else {
							request_busy(payload, replyTo, request);

						}
					} else if (accessType == AccessRequestType.EXCLUSIVE_WRITE_NONBLOCKING) {
						if (canHaveExclusiveWriteAccess(resourceName, replyTo)) {
							giveExclusiveWriteAccess(resourceName, replyTo, request);
						} else {
							request_busy(payload, replyTo, request);
						}
					}
				} else {
					request_busy(payload, replyTo, request);
				}
			} else {
				boolean check = false;
				int count = remoteManagers.size();
				
				for (ActorRef recipient : remoteManagers) {
					if(!discoverManagers.containsKey(recipient)) {
						WhoHasResourceRequestMsg temp = new WhoHasResourceRequestMsg(resourceName);
						WhoHasResourceResponseMsg t = new WhoHasResourceResponseMsg(temp,true,replyTo);
						
						if(t.getResult()){
							if (remoteResource.get(recipient) != null)
								remoteResource.get(recipient).add(resourceName);
							else {
								remoteResource.put(recipient, new HashSet<String>());
								remoteResource.get(recipient).add(resourceName);
							}
							
						}
					}
				}
				
				for (ActorRef recipient : remoteManagers) {
					
					if (remoteResource.get(recipient).contains(resourceName)) {
						if(!discoverManagers.containsKey(recipient)) {
							discoverManagers.put(recipient, new HashSet<String>());
							discoverManagers.get(recipient).add(resourceName);
							logger.tell(LogMsg.makeRemoteResourceDiscoveredLogMsg(getSelf(), 
									recipient, resourceName), getSender());
						} else if (!discoverManagers.get(recipient).contains(resourceName)){
							discoverManagers.get(recipient).add(resourceName);
							logger.tell(LogMsg.makeRemoteResourceDiscoveredLogMsg(getSelf(), 
									recipient, resourceName), getSender());
						}
						logger.tell(LogMsg.makeAccessRequestForwardedLogMsg(getSelf(), recipient, request), getSender());
						recipient.tell(msg, getSelf());
						check = true;
						break;
					}
				}
				if (!check) {
					AccessRequestDeniedMsg response = new AccessRequestDeniedMsg(payload,
							AccessRequestDenialReason.RESOURCE_NOT_FOUND);

					logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(getSelf(), replyTo, request, 
							AccessRequestDenialReason.RESOURCE_NOT_FOUND), getSelf());
					replyTo.tell(response, getSelf());
				}
			}
		}else if (msg instanceof AccessReleaseMsg) {
			AccessReleaseMsg payload = (AccessReleaseMsg) msg;
			AccessRelease accessRelease = payload.getAccessRelease();
			ActorRef sender = payload.getSender();

			String resourceName = accessRelease.getResourceName();
			logger.tell(LogMsg.makeAccessReleaseReceivedLogMsg(sender, getSelf(), accessRelease), getSender());

			if (localResources.containsKey(resourceName)) {
				boolean check = releaseAccess(resourceName, accessRelease.getType(), sender);
				if (check || 
						(hasAllAccessesReleased(resourceName) && toBeDisabledResources.containsKey(resourceName))) {
					
					AccessReleaseMsg response = new AccessReleaseMsg(accessRelease, sender);

					logger.tell(LogMsg.makeAccessReleasedLogMsg(sender, getSelf(), accessRelease), getSelf());
					sender.tell(response, getSelf());
				}

				processBlockingRequests(resourceName);
			} else {
				boolean check = false;

				for (ActorRef recipient : remoteManagers) {
					if (remoteResource.get(recipient).contains(resourceName)) {
						logger.tell(LogMsg.makeAccessReleaseForwardedLogMsg(getSelf(), recipient, accessRelease), getSender());
						recipient.tell(msg, getSelf());
						check = true;
						break;
					}
				}
				if (!check) {
					logger.tell(LogMsg.makeAccessReleaseIgnoredLogMsg(sender, getSelf(), accessRelease), getSelf());
				}
			}
		}   
		else if (msg instanceof WhoHasResourceResponseMsg) {
			WhoHasResourceResponseMsg payload = (WhoHasResourceResponseMsg) msg;
			System.out.println("I am here");
			if (payload.getResult()) {
				remoteResource.get(payload.getSender()).add(payload.getResourceName());
			}
			//getSelf().tell(msg, payload.getSender());
		}
		else if (msg instanceof AddInitialLocalResourcesRequestMsg) {
			AddInitialLocalResourcesRequestMsg payload = (AddInitialLocalResourcesRequestMsg) msg;
		
			for (Resource resource : payload.getLocalResources()) {
				resource.enable();
				localResources.put(resource.getName(), resource);
				logger.tell(LogMsg.makeLocalResourceCreatedLogMsg(getSelf(), resource.getName()), getSender());
/*				if(!remoteResource.containsKey(getSelf())) {
					remoteResource.put(getSelf(), new HashSet<String>());
					remoteResource.get(getSelf()).add(resource.getName());
				} else {
					remoteResource.get(getSelf()).add(resource.getName());
				}*/
			}
			AddInitialLocalResourcesResponseMsg response = new AddInitialLocalResourcesResponseMsg(payload);
			getSender().tell(response, getSelf());

		} else if (msg instanceof AddLocalUsersRequestMsg) {
			AddLocalUsersRequestMsg payload = (AddLocalUsersRequestMsg) msg;
			for (ActorRef user : payload.getLocalUsers()) {
				localUsers.add(user);
			}

			AddLocalUsersResponseMsg response = new AddLocalUsersResponseMsg(payload);
			getSender().tell(response, getSelf());
		} else if (msg instanceof AddRemoteManagersRequestMsg) {
			AddRemoteManagersRequestMsg payload = (AddRemoteManagersRequestMsg) msg;
			
			for (ActorRef manager : payload.getManagerList()) {
				if (!manager.equals(getSelf())) {
					remoteManagers.add(manager);
				}
			}

			AddRemoteManagersResponseMsg response = new AddRemoteManagersResponseMsg(payload);
			getSender().tell(response, getSelf());
		}
		else if (msg instanceof ManagementRequestMsg) {
			
			ManagementRequestMsg payload = (ManagementRequestMsg) msg;
			ManagementRequest request = payload.getRequest();
			ActorRef replyTo = payload.getReplyTo();

			String resourceName = request.getResourceName();
			ManagementRequestType type = request.getType();

			Resource localResource = localResources.get(resourceName);
			logger.tell(LogMsg.makeManagementRequestReceivedLogMsg(replyTo, getSelf(), request),replyTo);
			
			if (localResource != null) {
				if (type == ManagementRequestType.ENABLE) {
					System.out.println("I'm Enable");
					if (localResource.getStatus() == ResourceStatus.DISABLED) {
						localResource.enable();
					}
					ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(payload);
					logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, 
							getSelf(), request), getSender());
					logger.tell(LogMsg.makeResourceStatusChangedLogMsg(getSelf(), 
							resourceName, ResourceStatus.ENABLED), getSender());
					replyTo.tell(response, getSelf());
					
				} else if (type == ManagementRequestType.DISABLE) {
					if (hasConcurrentReadAccess(resourceName, replyTo)
							|| hasExclusiveWriteAccess(resourceName, replyTo)) {
						ManagementRequestDeniedMsg response = new ManagementRequestDeniedMsg(payload,
								ManagementRequestDenialReason.ACCESS_HELD_BY_USER);
						logger.tell(LogMsg.makeManagementRequestDeniedLogMsg(replyTo, getSelf(), request, 
								ManagementRequestDenialReason.ACCESS_HELD_BY_USER), getSender());
						replyTo.tell(response, getSelf());
					} else if ((resourceToReads.get(resourceName) == null
							|| resourceToReads.get(resourceName).isEmpty())
							&& (resourceToWrites.get(resourceName) == null
							|| resourceToWrites.get(resourceName).isEmpty())) {
						
						localResource.disable();
						/*ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(request);
						logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), request), getSender());
						replyTo.tell(response, getSelf());*/
						processBlockingRequests(resourceName);
						ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(request);
						logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), request), getSender());
						replyTo.tell(response, getSelf());
					} else {
						toBeDisabledResources.put(resourceName, payload);
						processBlockingRequests(resourceName);
					}
					ManagementRequestGrantedMsg response = new ManagementRequestGrantedMsg(request);
					logger.tell(LogMsg.makeManagementRequestGrantedLogMsg(replyTo, getSelf(), request), getSender());
					replyTo.tell(response, getSelf());
				}
			} else {
				boolean check = false;

				for (ActorRef recipient : remoteManagers) {
					if (remoteResource.get(recipient).contains(resourceName)) {
						logger.tell(LogMsg.makeManagementRequestForwardedLogMsg(replyTo, recipient, request), getSender());
						recipient.tell(msg, getSelf());
						check = true;
						break;
					}
				}
				if (!check) {
					logger.tell(LogMsg.makeManagementRequestDeniedLogMsg(replyTo, getSelf(), 
							request, ManagementRequestDenialReason.RESOURCE_NOT_FOUND), getSelf());
				}
			}
		} 
		
		else {
			unhandled(msg);
		}
	}

	private void request_busy(AccessRequestMsg payload, ActorRef replyTo, AccessRequest request) {

		AccessRequestDeniedMsg response = new AccessRequestDeniedMsg(payload,
				AccessRequestDenialReason.RESOURCE_BUSY);

		logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(getSelf(), replyTo, request, 
				AccessRequestDenialReason.RESOURCE_BUSY), getSelf());
		replyTo.tell(response, getSelf());

	}

	private void request_blocking(String resourceName, AccessRequestMsg payload, AccessRequest request, ActorRef replyTo) {
		Queue<AccessRequestMsg> blockingRequests = resourceToBlockingRequests.get(resourceName);
		if (blockingRequests == null) {
			blockingRequests = new LinkedList<AccessRequestMsg>();
		}
		blockingRequests.add(payload);
		resourceToBlockingRequests.put(resourceName, blockingRequests);
		//logger.tell(LogMsg.makeAccessRequestReceivedLogMsg(getSender(), getSelf(), request), replyTo);
		processBlockingRequests(resourceName);

	}

	private void request_denied(AccessRequestMsg payload, ActorRef replyTo, AccessRequest request) {
		//logger.tell(LogMsg.makeAccessRequestReceivedLogMsg(getSender(), getSelf(), request), replyTo);
		AccessRequestDeniedMsg response = new AccessRequestDeniedMsg(payload,
				AccessRequestDenialReason.RESOURCE_DISABLED);
		logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(getSelf(), replyTo, request, 
				AccessRequestDenialReason.RESOURCE_DISABLED), getSender());
		replyTo.tell(response, getSelf());

	}


	private boolean noOtherUserHasExclusiveWriteAccess(String resourceName, ActorRef user) {
		if (resourceToWrites.containsKey(resourceName)) {
			for (ActorRef temp : resourceToWrites.get(resourceName)) {
				if (!temp.equals(user))
					return false;
			}
		}
		return true;
	}

	private boolean noOtherUserHasConcurrentReadAccess(String resourceName, ActorRef user) {
		if (resourceToReads.containsKey(resourceName)) {
			for (ActorRef temp : resourceToReads.get(resourceName)) {
				if (!temp.equals(user))
					return false;
			}
		}
		return true;
	}

	private boolean canHaveConcurrentReadAccess(String resourceName, ActorRef user) {
		return noOtherUserHasExclusiveWriteAccess(resourceName, user);
	}

	private boolean canHaveExclusiveWriteAccess(String resourceName, ActorRef user) {
		return noOtherUserHasExclusiveWriteAccess(resourceName, user)
				&& noOtherUserHasConcurrentReadAccess(resourceName, user);
	}


	private void giveConcurrentReadAccess(String resourceName, ActorRef user, AccessRequest request) {
		List<ActorRef> allUsers = resourceToReads.get(resourceName);
		if (allUsers == null) {
			allUsers = new ArrayList<ActorRef>();
		}
		allUsers.add(user);
		resourceToReads.put(resourceName, allUsers);

		AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(request);

		logger.tell(LogMsg.makeAccessRequestGrantedLogMsg(user, getSelf(), request), getSender());
		user.tell(response, getSelf());
	}

	private void giveExclusiveWriteAccess(String resourceName, ActorRef user, AccessRequest request) {
		List<ActorRef> allUsers = resourceToWrites.get(resourceName);
		if (allUsers == null) {
			allUsers = new ArrayList<ActorRef>();
		}
		allUsers.add(user);
		resourceToWrites.put(resourceName, allUsers);

		AccessRequestGrantedMsg response = new AccessRequestGrantedMsg(request);

		logger.tell(LogMsg.makeAccessRequestGrantedLogMsg(user, getSelf(), request), getSender());
		user.tell(response, getSelf());
	}

	private void processBlockingRequests(String resourceName) {
		Queue<AccessRequestMsg> blockingRequests = resourceToBlockingRequests.get(resourceName);
		if (blockingRequests == null) {
			return;
		}

		while (!blockingRequests.isEmpty()) {
			AccessRequestMsg payload = blockingRequests.peek();
			AccessRequest request = payload.getAccessRequest();
			ActorRef replyTo = payload.getReplyTo();
			AccessRequestType accessType = request.getType();

			if (localResources.get(resourceName).getStatus() == ResourceStatus.DISABLED 
					|| toBeDisabledResources.containsKey(resourceName)) { 
				AccessRequestDeniedMsg response = new AccessRequestDeniedMsg(payload,
						AccessRequestDenialReason.RESOURCE_DISABLED);
				logger.tell(LogMsg.makeAccessRequestDeniedLogMsg(getSelf(), replyTo, request, 
						AccessRequestDenialReason.RESOURCE_DISABLED), getSender());
				replyTo.tell(response, getSelf());
			} else {
				if (accessType == AccessRequestType.CONCURRENT_READ_BLOCKING) {				
					if (canHaveConcurrentReadAccess(resourceName, replyTo)) {
						giveConcurrentReadAccess(resourceName, replyTo, request);
					} else {
						return;
					}
				}else if (accessType == AccessRequestType.EXCLUSIVE_WRITE_BLOCKING) {
					if (canHaveExclusiveWriteAccess(resourceName, replyTo)) {
						giveExclusiveWriteAccess(resourceName, replyTo, request);
					} else {
						return;
					}
				}
			}

			blockingRequests.remove();
		}
	}

	private boolean releaseAccess(String resourceName, AccessType accessType, ActorRef user) {
		if (accessType == AccessType.CONCURRENT_READ) {
			if (resourceToReads.containsKey(resourceName)) {
				List<ActorRef> list = resourceToReads.get(resourceName);
				if (list.contains(user)) {
					list.remove(user);
					return true;
				}
			}
		} else if (accessType == AccessType.EXCLUSIVE_WRITE) {
			if (resourceToWrites.containsKey(resourceName)) {
				List<ActorRef> list = resourceToWrites.get(resourceName);
				if (list.contains(user)) {
					list.remove(user);
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasAllAccessesReleased(String resourceName) {
		return (!resourceToReads.containsKey(resourceName) && !resourceToWrites.containsKey(resourceName));
	}

	private boolean hasConcurrentReadAccess(String resourceName, ActorRef user) {
		if (resourceToReads.containsKey(resourceName)) {
			for (ActorRef temp : resourceToReads.get(resourceName)) {
				if (temp.equals(user))
					return true;
			}
		}
		return false;
	}

	private boolean hasExclusiveWriteAccess(String resourceName, ActorRef user) {
		if (resourceToWrites.containsKey(resourceName)) {
			for (ActorRef temp : resourceToWrites.get(resourceName)) {
				if (temp.equals(user))
					return true;
			}
		}
		return false;
	}
}
