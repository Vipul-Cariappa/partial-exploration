package de.tum.in.pet.Converter;

import de.tum.in.probmodels.generator.RewardGenerator;
import explicit.MDP;
import parser.State;

import java.io.*;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Writes an MDP model into a prism file. Lists all the states and it's transitions.
 * If rewardGenerator is specified it also writes non-zero rewards of every state and state-action pair.
 */
public class MDPModelToPrismFileConverter {
    private final File targetFile;
    private final MDP mdpModel;
    private final RewardGenerator<State> rewardGenerator;
    private final List<State> stateList;

    private BufferedOutputStream bufferedOutputStream;

    public MDPModelToPrismFileConverter(File targetFile, MDP mdp, RewardGenerator<State> rewardGenerator, List<State> stateList){
       this.targetFile = targetFile;
       this.mdpModel = mdp;
       this.rewardGenerator = rewardGenerator;
       this.stateList = stateList;
    }

    public void safeWriteModel(){
        try {
            forceNewTargetFile();
            openOutputStream();
            writeModel();
            closeOutputStream();
        } catch (Exception e) {

            //Closing buffer stream might also throw errors.
            try {
                closeOutputStream();
            } catch (Exception streamException) {
                streamException.printStackTrace();
            }
            e.printStackTrace();
        }
    }

    private void writeModel() throws IOException {
        writeModelType();
        newLines(2);
        openModule();
        newLines(2);
        declareStates();
        newLines(1);
        writeTransitions();
        newLines(2);
        closeModule();
        newLines(2);

        if (rewardGenerator != null) {
            openRewardStructure();
            newLines(2);
            writeRewards();
            newLines(2);
            closeRewardStructure();
        }
    }

    private void writeModelType() throws IOException {
        writeToBuffer("mdp");
    }

    private void newLines(int n) throws IOException {
        for (int i = 0; i < n; i++) {
            writeToBuffer("\n");
        }
    }

    private void openModule() throws IOException {
        String moduleName = "default";
        writeToBuffer("module " + moduleName);
    }

    private void declareStates() throws IOException {
        int range = mdpModel.getNumStates() - 1;

        // TODO Multiple initial states?
        int firstInitialState = mdpModel.getFirstInitialState();
        String variable = "s: [0.." + range + "] init " + firstInitialState + ";";
        writeToBuffer(variable);
    }

    private void writeTransitions() throws IOException {
        int numStates = mdpModel.getNumStates();
        for (int state = 0; state < numStates; state++) {
            for (int choice = 0; choice < mdpModel.getNumChoices(state); choice++) {
                writeTransition(state, choice);
                newLines(1);
            }
        }
    }

    private void writeTransition(int state, int choice) throws IOException {
        Iterator<Map.Entry<Integer, Double>> transitionIterator = mdpModel.getTransitionsIterator(state, choice);
        StringBuilder transitionString = new StringBuilder();

        //Action label and state name
        Object actionLabel = mdpModel.getAction(state, choice);
        String actionLabelString = actionLabel == null ? "" : actionLabel.toString();
        transitionString
                .append("[")
                .append(actionLabelString)
                .append("] s=")
                .append(state)
                .append(" -> ");

        // Transitions
        while (transitionIterator.hasNext()) {
            Map.Entry<Integer, Double> transition = transitionIterator.next();
            int target = transition.getKey();
            double probability = transition.getValue();

            transitionString
                    .append(probability)
                    .append(":(s'=")
                    .append(target)
                    .append(")");


            if (transitionIterator.hasNext()) {
                transitionString.append(" + ");
            }
        }

        transitionString.append(";");

        writeToBuffer(transitionString.toString());
    }

    private void closeModule() throws IOException {
        writeToBuffer("endmodule");
    }

    private void openRewardStructure() throws IOException {
        writeToBuffer("rewards \"default_reward\"");
    }

    private void writeRewards() throws IOException {
        int numStates = mdpModel.getNumStates();
        for (int state = 0; state < numStates; state++) {
            for (int choice = 0; choice < mdpModel.getNumChoices(state); choice++) {
                writeReward(state, choice);
            }
        }
    }

    private void writeReward(int state, int choice) throws IOException {
        writeStateReward(state);
        writeTransitionReward(state, choice);
    }

    private void writeStateReward(int state) throws IOException {
        //TODO CHECK MAPPING STATES IS CORRECT
        double stateReward = rewardGenerator.stateReward(stateList.get(state));

        if (stateReward == 0d)
            return;

        String stateFormula = "s=" + state;
        writeToBuffer(stateFormula + " : " + stateReward + ";");
        newLines(1);
    }

    private void writeTransitionReward(int state, int choice) throws IOException {
        State s = stateList.get(state);
        Object actionLabel = mdpModel.getAction(state, choice);
        double transitionReward = rewardGenerator.transitionReward(s, actionLabel);

        if (transitionReward == 0d)
            return;

        String stateFormula = "s=" + state;
        String actionLabelString = actionLabel == null ? "" : actionLabel.toString();

        String transitionRewardString = "[" +
                actionLabelString +
                "] " +
                stateFormula +
                " : " +
                transitionReward +
                ";";

        writeToBuffer(transitionRewardString);
        newLines(1);
    }

    private void closeRewardStructure() throws IOException {
        writeToBuffer("endrewards");
    }

    private void forceNewTargetFile() throws IOException {
        Files.deleteIfExists(targetFile.toPath());
        Files.createDirectories(targetFile.getParentFile().toPath());
        Files.createFile(targetFile.toPath());
    }

    private void openOutputStream() throws FileNotFoundException {
        bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(targetFile));
    }

    private void closeOutputStream() throws IOException {
        if (bufferedOutputStream != null) {
            bufferedOutputStream.close();
        }
    }

    private void writeToBuffer(String string) throws IOException {
        bufferedOutputStream.write(string.getBytes());
    }
}