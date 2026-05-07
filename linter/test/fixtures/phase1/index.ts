import validBaseline from './validBaseline.bpmn';
import gen01Choreography from './gen01Choreography.bpmn';
import act12LoopWithoutAnnotation from './act12LoopWithoutAnnotation.bpmn';
import act13MiWithoutAnnotation from './act13MiWithoutAnnotation.bpmn';
import act12LoopWithEquivalentAnnotation from './act12LoopWithEquivalentAnnotation.bpmn';
import act13MiWithEquivalentAnnotation from './act13MiWithEquivalentAnnotation.bpmn';
import evt10StartWithIncoming from './evt10StartWithIncoming.bpmn';
import evt11MessageStartWithoutMessageFlow from './evt11MessageStartWithoutMessageFlow.bpmn';
import evt14InvalidBoundary from './evt14InvalidBoundary.bpmn';
import evt15UnmatchedErrorEnd from './evt15UnmatchedErrorEnd.bpmn';
import evt16UnpairedLink from './evt16UnpairedLink.bpmn';
import gtw11EventBasedToTask from './gtw11EventBasedToTask.bpmn';
import gtw12UnnamedDivergingFlow from './gtw12UnnamedDivergingFlow.bpmn';
import flow01CrossPoolSequence from './flow01CrossPoolSequence.bpmn';
import msg01SamePoolMessage from './msg01SamePoolMessage.bpmn';
import assoc01LoopWithoutAssociation from './assoc01LoopWithoutAssociation.bpmn';
import data01TypeWordsInDataName from './data01TypeWordsInDataName.bpmn';
import name03TypeWordsInElementName from './name03TypeWordsInElementName.bpmn';
import gen02DuplicateDiagram from './gen02DuplicateDiagram.bpmn';

export const phase1Fixtures = {
  validBaseline,
  gen01Choreography,
  act12LoopWithoutAnnotation,
  act13MiWithoutAnnotation,
  act12LoopWithEquivalentAnnotation,
  act13MiWithEquivalentAnnotation,
  evt10StartWithIncoming,
  evt11MessageStartWithoutMessageFlow,
  evt14InvalidBoundary,
  evt15UnmatchedErrorEnd,
  evt16UnpairedLink,
  gtw11EventBasedToTask,
  gtw12UnnamedDivergingFlow,
  flow01CrossPoolSequence,
  msg01SamePoolMessage,
  assoc01LoopWithoutAssociation,
  data01TypeWordsInDataName,
  name03TypeWordsInElementName,
  gen02DuplicateDiagram,
};
