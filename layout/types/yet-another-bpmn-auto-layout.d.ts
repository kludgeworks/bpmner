declare module "yet-another-bpmn-auto-layout" {
	/**
	 * Applies auto-layout to a BPMN 2.0 XML string.
	 * @param xml - The BPMN XML string to layout.
	 * @returns A Promise that resolves to the layouted XML string.
	 */
	export function layoutProcess(xml: string): Promise<string>
}
