package cmsc433.p4.messages;

public class HasResourceMsg {
	private Object originalMsg;
	
	public HasResourceMsg(Object originalMsg) {
		this.originalMsg = originalMsg;
	}
	
	public Object getOriginalRequest() {
		return originalMsg;
	}
}