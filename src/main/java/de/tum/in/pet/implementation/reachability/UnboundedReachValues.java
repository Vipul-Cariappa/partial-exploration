package de.tum.in.pet.implementation.reachability;

import static com.google.common.base.Preconditions.checkArgument;
import static de.tum.in.probmodels.util.Util.isOne;
import static de.tum.in.probmodels.util.Util.isZero;

import de.tum.in.pet.sampler.SuccessorHeuristic;
import de.tum.in.pet.sampler.UnboundedValues;
import de.tum.in.pet.util.SampleUtil;
import de.tum.in.pet.values.Bounds;
import de.tum.in.probmodels.model.Distribution;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterators;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.function.ToDoubleFunction;

public class UnboundedReachValues implements UnboundedValues {
  private final Int2ObjectMap<Bounds> bounds = new Int2ObjectOpenHashMap<>();
  private final ValueUpdate update;
  private final IntPredicate target;
  private final double precision;
  private final SuccessorHeuristic heuristic;

  public UnboundedReachValues(ValueUpdate update, IntPredicate target, double precision,
      SuccessorHeuristic heuristic) {
    this.update = update;
    this.target = target;
    this.precision = precision;
    this.heuristic = heuristic;
  }

  @Override
  public boolean isSmallestFixPoint() {
    return update == ValueUpdate.MIN_VALUE;
  }

  @Override
  public Bounds bounds(int state) {
    return target.test(state)
        ? Bounds.reachOne()
        : bounds.getOrDefault(state, Bounds.reachUnknown());
  }

  public double lowerBound(int state) {
    if (target.test(state)) {
      return 1.0d;
    }
    Bounds bounds = this.bounds.get(state);
    return bounds == null ? 0.0d : bounds.lowerBound();
  }

  public double upperBound(int state) {
    if (target.test(state)) {
      return 1.0d;
    }
    Bounds bounds = this.bounds.get(state);
    return bounds == null ? 1.0d : bounds.upperBound();
  }


  @Override
  public boolean isSolved(int state) {
    return bounds(state).difference() < precision;
  }

  @Override
  public boolean isUnknown(int state) {
    return isOne(bounds(state).difference());
  }

  @Override
  public int sampleNextState(int state, List<Distribution> choices) {
    ToDoubleFunction<Distribution> actionScore = isSmallestFixPoint()
        ? d -> 1.0d - d.sumWeighted(this::lowerBound)
        : d -> d.sumWeighted(this::upperBound);
    IntToDoubleFunction successorDifferences = s -> bounds(s).difference();

    return SampleUtil.sampleNextState(choices, heuristic, actionScore, successorDifferences);
  }

  @Override
  public void collapse(int representative, List<Distribution> choices, IntSet collapsed) {
    bounds.keySet().removeAll(collapsed);

    if (isSmallestFixPoint()) {
      // Only collapse bottom components
      checkArgument(choices.isEmpty());
    }

    if (IntIterators.any(collapsed.iterator(), target)) {
      bounds.put(representative, Bounds.reachOne());
    } else {
      update(representative, choices);
    }
  }

  private Bounds successorBounds(int state, Distribution distribution) {
    double lower = 0.0d;
    double upper = 0.0d;
    double sum = 0.0d;
    for (Int2DoubleMap.Entry entry : distribution) {
      int successor = entry.getIntKey();
      if (successor == state) {
        continue;
      }
      Bounds successorBounds = bounds(successor);
      double probability = entry.getDoubleValue();
      sum += probability;
      lower += successorBounds.lowerBound() * probability;
      upper += successorBounds.upperBound() * probability;
    }
    if (sum == 0.0d) {
      return bounds(state);
    }
    return Bounds.reach(lower / sum, upper / sum);
  }

  @Override
  public void update(int state, List<Distribution> choices) {
    assert update != ValueUpdate.UNIQUE_VALUE || choices.size() <= 1;

    Bounds stateBounds = bounds(state);
    if (isOne(stateBounds.lowerBound()) || isZero(stateBounds.upperBound())) {
      return;
    }
    assert !target.test(state);

    Bounds oldBounds;
    Bounds newBounds;
    if (choices.isEmpty()) {
      newBounds = Bounds.reachZero();
      oldBounds = bounds.put(state, newBounds);
    } else if (choices.size() == 1) {
      newBounds = successorBounds(state, choices.get(0));
      oldBounds = bounds.put(state, newBounds);
    } else {
      double newLowerBound;
      double newUpperBound;

      if (update == ValueUpdate.MAX_VALUE) {
        newLowerBound = 0.0d;
        newUpperBound = 0.0d;
        for (Distribution distribution : choices) {
          Bounds bounds = successorBounds(state, distribution);
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
        assert update == ValueUpdate.MIN_VALUE;

        newUpperBound = 1.0d;
        newLowerBound = 1.0d;
        for (Distribution distribution : choices) {
          Bounds bounds = successorBounds(state, distribution);
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
      newBounds = Bounds.of(newLowerBound, newUpperBound);
      oldBounds = bounds.put(state, newBounds);
    }
    assert oldBounds == null || oldBounds.contains(newBounds);
  }

  @Override
  public void explored(int state) {
    // empty
  }
}
