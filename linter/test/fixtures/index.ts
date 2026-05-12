import act01Invalid from "./act01Invalid.bpmn"
import act01PhrasalVerbValid from "./act01PhrasalVerbValid.bpmn"
import act01UppercaseLabelValid from "./act01UppercaseLabelValid.bpmn"
import act01Valid from "./act01Valid.bpmn"
import act02Invalid from "./act02Invalid.bpmn"
import act02Valid from "./act02Valid.bpmn"
import act03Invalid from "./act03Invalid.bpmn"
import act03Valid from "./act03Valid.bpmn"
import act12LoopWithEquivalentAnnotation from "./act12LoopWithEquivalentAnnotation.bpmn"
import act12LoopWithoutAnnotation from "./act12LoopWithoutAnnotation.bpmn"
import act13MiWithEquivalentAnnotation from "./act13MiWithEquivalentAnnotation.bpmn"
import act13MiWithoutAnnotation from "./act13MiWithoutAnnotation.bpmn"
import assoc01LoopWithoutAssociation from "./assoc01LoopWithoutAssociation.bpmn"
import data01TypeWordsInDataName from "./data01TypeWordsInDataName.bpmn"
import evt01Invalid from "./evt01Invalid.bpmn"
import evt01Valid from "./evt01Valid.bpmn"
import evt02Invalid from "./evt02Invalid.bpmn"
import evt02Valid from "./evt02Valid.bpmn"
import evt10StartWithIncoming from "./evt10StartWithIncoming.bpmn"
import evt11MessageStartWithoutMessageFlow from "./evt11MessageStartWithoutMessageFlow.bpmn"
import evt12TimerStartMissingExpression from "./evt12TimerStartMissingExpression.bpmn"
import evt12TimerStartMultipleExpressions from "./evt12TimerStartMultipleExpressions.bpmn"
import evt12TimerStartValid from "./evt12TimerStartValid.bpmn"
import evt13Invalid from "./evt13Invalid.bpmn"
import evt13Valid from "./evt13Valid.bpmn"
import evt14InvalidBoundary from "./evt14InvalidBoundary.bpmn"
import evt15UnmatchedErrorEnd from "./evt15UnmatchedErrorEnd.bpmn"
import evt16UnpairedLink from "./evt16UnpairedLink.bpmn"
import flow01CrossPoolSequence from "./flow01CrossPoolSequence.bpmn"
import flow02Invalid from "./flow02Invalid.bpmn"
import flow02Valid from "./flow02Valid.bpmn"
import gen01Choreography from "./gen01Choreography.bpmn"
import gen02DuplicateDiagram from "./gen02DuplicateDiagram.bpmn"
import gtw01Invalid from "./gtw01Invalid.bpmn"
import gtw01Valid from "./gtw01Valid.bpmn"
import gtw01ValidNoQuestionMark from "./gtw01ValidNoQuestionMark.bpmn"
import gtw02Invalid from "./gtw02Invalid.bpmn"
import gtw02Valid from "./gtw02Valid.bpmn"
import gtw03Invalid from "./gtw03Invalid.bpmn"
import gtw03Valid from "./gtw03Valid.bpmn"
import gtw10ExclusiveValid from "./gtw10ExclusiveValid.bpmn"
import gtw10InclusiveValid from "./gtw10InclusiveValid.bpmn"
import gtw10ParallelConditionalInvalid from "./gtw10ParallelConditionalInvalid.bpmn"
import gtw10ParallelValid from "./gtw10ParallelValid.bpmn"
import gtw11EventBasedToTask from "./gtw11EventBasedToTask.bpmn"
import gtw12UnnamedDivergingFlow from "./gtw12UnnamedDivergingFlow.bpmn"
import gtw20JoinForkFixable from "./gtw20JoinForkFixable.bpmn"
import gtw21FakeJoinFixable from "./gtw21FakeJoinFixable.bpmn"
import gtw22SuperfluousGatewayFixable from "./gtw22SuperfluousGatewayFixable.bpmn"
import msg01SamePoolMessage from "./msg01SamePoolMessage.bpmn"
import msg02Invalid from "./msg02Invalid.bpmn"
import msg02PastParticipleNounValid from "./msg02PastParticipleNounValid.bpmn"
import msg02UppercaseVerbInvalid from "./msg02UppercaseVerbInvalid.bpmn"
import msg02Valid from "./msg02Valid.bpmn"
import name01Invalid from "./name01Invalid.bpmn"
import name01Valid from "./name01Valid.bpmn"
import name02FixableAbbrev from "./name02FixableAbbrev.bpmn"
import name02Invalid from "./name02Invalid.bpmn"
import name02Valid from "./name02Valid.bpmn"
import name03TypeWordsInElementName from "./name03TypeWordsInElementName.bpmn"
import noDuplicateFlowFixable from "./noDuplicateFlowFixable.bpmn"
import singleBlankStartEventFixable from "./singleBlankStartEventFixable.bpmn"
import singleEventDefinitionFixable from "./singleEventDefinitionFixable.bpmn"
import superfluousTerminationFixable from "./superfluousTerminationFixable.bpmn"
import validBaseline from "./validBaseline.bpmn"

export const fixtures = {
	validBaseline,
	gen01Choreography,
	gen02DuplicateDiagram,
	act12LoopWithoutAnnotation,
	act12LoopWithEquivalentAnnotation,
	act13MiWithoutAnnotation,
	act13MiWithEquivalentAnnotation,
	act01Invalid,
	act01Valid,
	act01PhrasalVerbValid,
	act01UppercaseLabelValid,
	act02Invalid,
	act02Valid,
	act03Invalid,
	act03Valid,
	evt10StartWithIncoming,
	evt11MessageStartWithoutMessageFlow,
	evt12TimerStartMissingExpression,
	evt12TimerStartMultipleExpressions,
	evt12TimerStartValid,
	evt13Invalid,
	evt13Valid,
	evt14InvalidBoundary,
	evt15UnmatchedErrorEnd,
	evt16UnpairedLink,
	evt01Invalid,
	evt01Valid,
	evt02Invalid,
	evt02Valid,
	gtw11EventBasedToTask,
	gtw12UnnamedDivergingFlow,
	gtw01Invalid,
	gtw01Valid,
	gtw01ValidNoQuestionMark,
	gtw02Invalid,
	gtw02Valid,
	gtw03Invalid,
	gtw03Valid,
	gtw10ExclusiveValid,
	gtw10InclusiveValid,
	gtw10ParallelConditionalInvalid,
	gtw10ParallelValid,
	flow01CrossPoolSequence,
	flow02Invalid,
	flow02Valid,
	msg01SamePoolMessage,
	msg02Invalid,
	msg02Valid,
	msg02UppercaseVerbInvalid,
	msg02PastParticipleNounValid,
	assoc01LoopWithoutAssociation,
	data01TypeWordsInDataName,
	name01Invalid,
	name01Valid,
	name02Invalid,
	name02Valid,
	name03TypeWordsInElementName,
	name02FixableAbbrev,
	superfluousTerminationFixable,
	noDuplicateFlowFixable,
	singleBlankStartEventFixable,
	singleEventDefinitionFixable,
	gtw22SuperfluousGatewayFixable,
	gtw21FakeJoinFixable,
	gtw20JoinForkFixable,
} as Record<string, string>
