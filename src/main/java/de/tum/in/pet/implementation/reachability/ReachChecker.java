package de.tum.in.pet.implementation.reachability;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static de.tum.in.pet.util.PrismHelper.parse;

import de.tum.in.naturals.set.DefaultNatBitSetFactory;
import de.tum.in.naturals.set.NatBitSets;
import de.tum.in.pet.explorer.DefaultExplorer;
import de.tum.in.pet.explorer.Explorer;
import de.tum.in.pet.generator.CtmcEmbeddingGenerator;
import de.tum.in.pet.generator.DtmcGenerator;
import de.tum.in.pet.generator.Generator;
import de.tum.in.pet.generator.MdpGenerator;
import de.tum.in.pet.generator.SafetyGenerator;
import de.tum.in.pet.graph.ComponentAnalyser;
import de.tum.in.pet.graph.MecComponentAnalyser;
import de.tum.in.pet.graph.SccComponentAnalyser;
import de.tum.in.pet.model.MarkovChain;
import de.tum.in.pet.model.MarkovDecisionProcess;
import de.tum.in.pet.model.Model;
import de.tum.in.pet.sampler.BoundedSampler;
import de.tum.in.pet.sampler.Sampler;
import de.tum.in.pet.sampler.SuccessorHeuristic;
import de.tum.in.pet.sampler.UnboundedSampler;
import de.tum.in.pet.util.CliHelper;
import de.tum.in.pet.util.PrismExpressionWrapper;
import de.tum.in.pet.util.PrismHelper.PrismParseResult;
import de.tum.in.pet.util.Result;
import de.tum.in.pet.util.Util;
import de.tum.in.pet.values.ValueInterpretation;
import de.tum.in.pet.values.bounded.StateValuesBounded;
import de.tum.in.pet.values.unbounded.StateValues;
import it.unimi.dsi.fastutil.ints.IntCollection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import parser.State;
import parser.Values;
import parser.ast.ASTElement;
import parser.ast.Expression;
import parser.ast.ExpressionBinaryOp;
import parser.ast.ExpressionTemporal;
import parser.ast.ModulesFile;
import parser.ast.PropertiesFile;
import prism.ModelGenerator;
import prism.ModelType;
import prism.Prism;
import prism.PrismDevNullLog;
import prism.PrismException;
import simulator.ModulesFileModelGenerator;

@SuppressWarnings("PMD")
public final class ReachChecker {
  private static final Logger logger = Logger.getLogger(ReachChecker.class.getName());

  private ReachChecker() {
    // Empty
  }

  private static <S, R> void printResult(Result<S, R> result) {
    Collection<S> states = result.states();

    if (states.size() > 1) {
      for (S state : states) {
        R value = result.get(state);
        String format = value instanceof Double ? "%s %.10g%n" : "%s %s%n";
        System.out.printf(format, state, value);
      }
    } else {
      S state = states.iterator().next();
      R value = result.get(state);
      String format = value instanceof Double ? "%.10g%n" : "%s%n";
      System.out.printf(format, value);
    }
  }

  private static <S, R> boolean validateResult(Result<S, R> result, String expected,
      double precision, boolean relativeError) {
    Collection<S> states = result.states();
    if (states.size() != 1) {
      throw new IllegalArgumentException();
    }
    R value = result.get(states.iterator().next());

    if (value instanceof Boolean) {
      boolean expectedValue = Boolean.parseBoolean(expected);
      if (expectedValue != (Boolean) value) {
        System.out.printf("Expected %s, got %s", expected, value);
        return false;
      }
    } else if (value instanceof Double) {
      double computed = (Double) value;
      double expectedValue = Double.parseDouble(expected);
      if (relativeError) {
        double expectedLower = (1 - precision) * expectedValue;
        double expectedUpper = (1 + precision) * expectedValue;
        if (computed < expectedLower || computed > expectedUpper) {
          System.out.printf("Expected in range [%.6g, %.6g] but got %.6g, "
                  + "actual precision %.6g > required precision %.6g%n",
              expectedLower, expectedUpper, computed,
              Math.abs(1 - computed / expectedValue), precision);
          return false;
        } else {
          System.out.printf("Expected in range [%.6g, %.6g], got %.6g, "
                  + "actual precision: %.6g%n",
              computed, expectedLower, expectedUpper, Math.abs(1 - computed / expectedValue));
        }
      } else {
        double difference = Math.abs(expectedValue - computed);
        if (difference >= precision) {
          System.out.printf("Expected %.6g but got %.6g (difference %.6g > precision %.6g%n",
              expectedValue, computed, difference, precision);
          return false;
        } else {
          System.out.printf("Expected %.6g, got %.6g, actual precision: %.6g%n",
              expectedValue, computed, difference);
        }
      }
    }
    return true;
  }

  private static Result<?, ?> solve(ModelGenerator generator, PrismQuery<?> expression,
      SuccessorHeuristic heuristic) throws PrismException {
    ModelType modelType = generator.getModelType();
    switch (modelType) {
      case CTMC:
        return solveCtmc(generator, expression, heuristic);
      case DTMC:
        return solveDtmc(generator, expression, heuristic);
      case MDP:
        return solveMdp(generator, expression, heuristic);
      case LTS:
      case CTMDP:
      case PTA:
      case STPG:
      case SMG:
      default:
        throw new UnsupportedOperationException();
    }
  }


  private static <S, M extends Model, R> Result<S, R> solve(StateValues values,
      Explorer<S, M> explorer, SuccessorHeuristic heuristic,
      ComponentAnalyser componentAnalyser, IntPredicate predicate, QueryType<R> type)
      throws PrismException {
    StateUpdateReachability stateUpdate = new StateUpdateReachability(predicate, type.update());
    UnboundedSampler<S, M> sampler = new UnboundedSampler<>(explorer, values, heuristic,
        stateUpdate, type.verdict(), componentAnalyser);

    logger.log(Level.INFO, "Checking expression {0} {1}", new Object[] {predicate, type});
    sampler.build();

    return makeResult(sampler, type.interpretation(), explorer);
  }

  private static <S, R> Result<S, R> makeResult(Sampler<S, ?> sampler,
      ValueInterpretation<R> interpretation, Explorer<S, ?> explorer) {
    IntCollection initialStateIds = explorer.initialStates();
    Map<S, R> results = new HashMap<>();
    for (int initialState : initialStateIds) {
      S state = explorer.getState(initialState);
      R value = interpretation.interpret(sampler.bounds(initialState));
      results.put(state, value);
    }
    return Result.of(results);
  }

  private static <S, M extends Model, R> Result<S, R> solveBounded(StateValuesBounded values,
      Explorer<S, M> explorer, SuccessorHeuristic heuristic, IntPredicate predicate,
      QueryType<R> type, int stepBound)
      throws PrismException {
    StateUpdateBoundedReachability stateUpdate =
        new StateUpdateBoundedReachability(predicate, type.update());
    BoundedSampler<S, M> sampler = new BoundedSampler<>(new Prism(new PrismDevNullLog()),
        explorer, stepBound, stateUpdate, type.verdict(), heuristic, values);

    logger.log(Level.INFO, "Checking expression {0} {1}", new Object[] {predicate, type});
    sampler.build();

    return makeResult(sampler, type.interpretation(), explorer);
  }

  private static <M extends Model, R> Result<?, R> solve(PrismQuery<R> query,
      ComponentAnalyser componentAnalyser, M partialModel, Generator<State> generator,
      SuccessorHeuristic heuristic) throws PrismException {
    if (query.isBounded()) {
      ExpressionTemporal prismExpression = query.expression();
      checkArgument(prismExpression.getOperator() == ExpressionTemporal.P_F);

      Expression right = prismExpression.getOperand2();
      var explorer = new DefaultExplorer<>(partialModel, generator);
      var predicate = new StateToIntTarget<>(new PrismExpressionWrapper(right), explorer::getState);
      StateValuesBounded stateValuesBounded = new StateValuesBoundedReachability(predicate);

      return solveBounded(stateValuesBounded, explorer, heuristic, predicate, query.type(),
          query.upperBound());
    }

    ExpressionTemporal prismExpression = query.expression();

    // TODO Until form, simple cases (lhs = true/false, rhs = true/false ...)

    Expression right = prismExpression.getOperand2();
    if (prismExpression.getOperator() == ExpressionTemporal.P_F) {
      var explorer = new DefaultExplorer<>(partialModel, generator);
      var predicate = new StateToIntTarget<>(new PrismExpressionWrapper(right), explorer::getState);
      StateValues stateValues = new StateValuesReachability(predicate);
      return solve(stateValues, explorer, heuristic, componentAnalyser, predicate, query.type());
    }

    Expression left = prismExpression.getOperand1();
    Expression safety = new ExpressionBinaryOp(ExpressionBinaryOp.OR, left, right);

    ASTElement simplify = safety.simplify();
    if (simplify instanceof Expression) {
      safety = (Expression) simplify;
    } else {
      logger.log(Level.WARNING, "Can't simplify expression {0}", safety);
    }

    var productGenerator = new SafetyGenerator<>(generator, new PrismExpressionWrapper(safety));
    var predicate = new UntilTargetPredicate<>(new PrismExpressionWrapper(right));
    var explorer = new DefaultExplorer<>(partialModel, productGenerator);
    var productPredicate = new StateToIntTarget<>(predicate, explorer::getState);
    StateValues stateValues = new StateValuesReachability(productPredicate);

    return solve(stateValues, explorer, heuristic, componentAnalyser, productPredicate,
        query.type());
  }

  private static <R> Result<?, R> solveMdp(ModelGenerator prismGenerator,
      PrismQuery<R> expression, SuccessorHeuristic heuristic) throws PrismException {
    MarkovDecisionProcess partialModel = new MarkovDecisionProcess();
    ComponentAnalyser componentAnalyser = new MecComponentAnalyser();
    Generator<State> generator = new MdpGenerator(prismGenerator);
    return solve(expression, componentAnalyser, partialModel, generator, heuristic);
  }

  private static <R> Result<?, R> solveCtmc(ModelGenerator prismGenerator,
      PrismQuery<R> expression, SuccessorHeuristic heuristic) throws PrismException {
    MarkovChain partialModel = new MarkovChain();
    ComponentAnalyser componentAnalyser = new SccComponentAnalyser();
    Generator<State> generator = new CtmcEmbeddingGenerator(prismGenerator);
    return solve(expression, componentAnalyser, partialModel, generator, heuristic);
  }

  private static <R> Result<?, R> solveDtmc(ModelGenerator prismGenerator,
      PrismQuery<R> expression, SuccessorHeuristic heuristic) throws PrismException {
    MarkovChain partialModel = new MarkovChain();
    ComponentAnalyser componentAnalyser = new SccComponentAnalyser();
    Generator<State> generator = new DtmcGenerator(prismGenerator);
    return solve(expression, componentAnalyser, partialModel, generator, heuristic);
  }

  public static void main(String... args) throws IOException, PrismException {
    Option precisionOption = new Option(null, "precision", true, "Precision");
    Option heuristicOption = CliHelper.getDefaultHeuristicOption();
    Option modelOption = new Option("m", "model", true, "Path to model file");
    Option propertiesOption = new Option("p", "properties", true, "Path to properties file");
    Option propertyNameOption = new Option(null, "property", true, "Name of property to check");
    Option constantsOption = new Option("c", "const", true,
        "Constants of model/property file, comma separated list");
    Option expectedValuesOption = new Option(null, "expected", true,
        "Comma separated list of the true values of the properties");
    Option onlyPrintResultOption = new Option(null, "only-result", false,
        "Only print result");
    Option relativeErrorOption = new Option(null, "relative-error", false,
        "Use relative error estimate");

    modelOption.setRequired(true);
    propertiesOption.setRequired(true);

    Options options = new Options()
        .addOption(precisionOption)
        .addOption(heuristicOption)
        .addOption(modelOption)
        .addOption(propertiesOption)
        .addOption(propertyNameOption)
        .addOption(expectedValuesOption)
        .addOption(constantsOption)
        .addOption(onlyPrintResultOption)
        .addOption(relativeErrorOption);

    CommandLine commandLine = CliHelper.parse(options, args);

    boolean relativeError = commandLine.hasOption(relativeErrorOption.getLongOpt());
    double precision = commandLine.hasOption(precisionOption.getLongOpt())
        ? Double.parseDouble(commandLine.getOptionValue(precisionOption.getLongOpt()))
        : Util.DEFAULT_PRECISION;

    SuccessorHeuristic heuristic = CliHelper.parseHeuristic(
        commandLine.getOptionValue(heuristicOption.getLongOpt()), SuccessorHeuristic.WEIGHTED);

    NatBitSets.setFactory(new DefaultNatBitSetFactory((a, b) -> true));
    // NatBitSets.setFactory(new RoaringNatBitSetFactory());

    PrismParseResult parse = parse(commandLine, modelOption, propertiesOption, constantsOption);
    ModulesFile modulesFile = parse.modulesFile();
    PropertiesFile propertiesFile = checkNotNull(parse.propertiesFile());

    Prism prism = new Prism(new PrismDevNullLog());

    ModelGenerator generator = new ModulesFileModelGenerator(modulesFile, prism);

    List<Expression> expressions;
    if (commandLine.hasOption(propertyNameOption.getLongOpt())) {
      String propertyName = commandLine.getOptionValue(propertyNameOption.getLongOpt());
      int index = propertiesFile.getPropertyIndexByName(propertyName);
      if (index == -1) {
        System.out.println("No property found for name " + propertyName);
        System.exit(1);
      }

      Expression expression = parse.expressions().get(index);
      expressions = Collections.singletonList(expression);
    } else {
      expressions = parse.expressions();
    }

    List<String> expressionExpected;
    if (commandLine.hasOption(expectedValuesOption.getLongOpt())) {
      String expectedValuesString = commandLine.getOptionValue(expectedValuesOption.getLongOpt());
      expressionExpected = Arrays.asList(expectedValuesString.split(","));
      if (expressions.size() != expressionExpected.size()) {
        throw new IllegalArgumentException("Differing lengths");
      }
    } else {
      expressionExpected = Collections.emptyList();
    }

    List<PrismQuery<?>> prismQueries = new ArrayList<>(expressions.size());
    Values values = parse.constants().getPFConstantValues();
    for (Expression expression : expressions) {
      prismQueries.add(PrismQuery.parse(expression, values, precision, relativeError));
    }

    checkArgument(!prismQueries.isEmpty(), "No valid expression found");

    List<Result<?, ?>> results = new ArrayList<>();
    for (PrismQuery<?> expression : prismQueries) {
      Result<?, ?> result = solve(generator, expression, heuristic);
      results.add(result);
    }

    if (!expressionExpected.isEmpty()) {
      boolean allCorrect = true;
      for (int i = 0; i < results.size(); i++) {
        Result<?, ?> result = results.get(i);
        String expected = expressionExpected.get(i);
        allCorrect &= validateResult(result, expected, precision, relativeError);
      }
      if (!allCorrect) {
        System.exit(1);
      }
    }


    results.forEach(ReachChecker::printResult);
  }
}
