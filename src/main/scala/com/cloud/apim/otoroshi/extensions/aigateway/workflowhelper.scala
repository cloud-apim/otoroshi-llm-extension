package otoroshi.next.workflow


object WorkflowHelper {
  def getWorkflow(ext: WorkflowAdminExtension, ref: String): Option[Workflow] = {
    ext.states.workflow(ref)
  }
}
