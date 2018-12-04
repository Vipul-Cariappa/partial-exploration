package de.tum.in.pet.implementation.reachability;

import static com.google.common.base.Preconditions.checkArgument;

import de.tum.in.pet.model.Distribution;
import de.tum.in.pet.values.Bounds;
import de.tum.in.pet.values.InitialValues;
import de.tum.in.pet.values.StateUpdate;
import de.tum.in.pet.values.StateValueFunction;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntIterator;
import java.util.List;
import java.util.function.IntFunction;
import parser.State;
import prism.PrismException;

public class StateUpdateReachability implements StateUpdate, InitialValues {
  private final IntFunction<State> numberToStateFunction;
  private final TargetPredicate target;
  private final ValueUpdateType update;

  public StateUpdateReachability(IntFunction<State> numberToStateFunction, TargetPredicate target,
      ValueUpdateType update) {
    this.numberToStateFunction = numberToStateFunction;
    this.target = target;
    this.update = update;
  }

  @Override
  public Bounds update(int state, List<Distribution> choices, StateValueFunction values)
      throws PrismException {
    assert update != ValueUpdateType.UNIQUE_VALUE || choices.size() <= 1;

    if (values.lowerBound(state) == 1.0d) {
      assert values.upperBound(state) == 1.0d;
      return Bounds.ONE_ONE;
    }
    if (values.upperBound(state) == 0.0d) {
      assert values.lowerBound(state) == 0.0d;
      return Bounds.ZERO_ZERO;
    }
    assert !target.isTargetState(numberToStateFunction.apply(state));

    if (choices.isEmpty()) {
      return Bounds.ZERO_ZERO;
    }
    if (choices.size() == 1) {
      return values.bounds(state, choices.get(0));
    }

    double newLowerBound;
    double newUpperBound;
    if (update == ValueUpdateType.MAX_VALUE) {
      newLowerBound = 0.0d;
      newUpperBound = 0.0d;
      for (Distribution distribution : choices) {
        Bounds bounds = values.bounds(state, distribution);
        double upperBound = bounds.upperBound();
        if (upperBound > newUpperBound) {
          newUpperBound = upperBound;
        }
        double lowerBound = bounds.lowerBound();
        if (lowerBound > newLowerBound) {
          newLowerBound = lowerBound;
        }
      }
    } else {
      assert update == ValueUpdateType.MIN_VALUE;

      newUpperBound = 1.0d;
      newLowerBound = 1.0d;
      for (Distribution distribution : choices) {
        Bounds bounds = values.bounds(state, distribution);
        double upperBound = bounds.upperBound();
        if (upperBound < newUpperBound) {
          newUpperBound = upperBound;
        }
        double lowerBound = bounds.lowerBound();
        if (lowerBound < newLowerBound) {
          newLowerBound = lowerBound;
        }
      }
    }
    assert newLowerBound <= newUpperBound;
    return Bounds.of(newLowerBound, newUpperBound);
  }

  @Override
  public Bounds updateCollapsed(int state, List<Distribution> choices,
      IntCollection collapsedStates, StateValueFunction values) throws PrismException {
    if (update == ValueUpdateType.MIN_VALUE) {
      checkArgument(choices.isEmpty());
    }

    IntIterator iterator = collapsedStates.iterator();
    while (iterator.hasNext()) {
      int next = iterator.nextInt();
      if (target.isTargetState(numberToStateFunction.apply(next))) {
        return Bounds.ONE_ONE;
      }
    }
    return update(state, choices, values);
  }

  @Override
  public boolean isSmallestFixPoint() {
    return update == ValueUpdateType.MIN_VALUE;
  }

  @Override
  public Bounds initialValues(State state) throws PrismException {
    return target.isTargetState(state) ? Bounds.ONE_ONE : Bounds.ZERO_ONE;
  }
}
