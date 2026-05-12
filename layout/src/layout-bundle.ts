import { layoutProcess } from "yet-another-bpmn-auto-layout"

type LayoutGlobal = typeof globalThis & {
	BpmnLayoutApi?: {
		layoutXml(xmlString: string): Promise<string>
	}
}

/**
 * Expose layout API to global scope for GraalVM access.
 */
const layoutGlobal = globalThis as LayoutGlobal

layoutGlobal.BpmnLayoutApi = {
	/**
	 * Applies auto-layout to the provided BPMN XML string.
	 * Returns a Promise that resolves to the layouted XML.
	 */
	layoutXml: async (xmlString: string): Promise<string> => {
		try {
			return await layoutProcess(xmlString)
		} catch (error) {
			console.error("BPMN Auto-layout failed:", error)
			throw error
		}
	},
}
