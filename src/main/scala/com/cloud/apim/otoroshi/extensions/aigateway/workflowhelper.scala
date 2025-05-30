package otoroshi.next.workflow

import otoroshi.env.Env

object WorkflowHelper {
  def getWorkflow(ext: WorkflowAdminExtension, ref: String): Option[Workflow] = {
    ext.states.workflow(ref)
  }
}
