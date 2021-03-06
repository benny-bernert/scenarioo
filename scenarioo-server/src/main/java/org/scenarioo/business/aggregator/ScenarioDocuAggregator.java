/* scenarioo-server
 * Copyright (C) 2014, scenarioo.org Development Team
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.scenarioo.business.aggregator;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.scenarioo.api.ScenarioDocuReader;
import org.scenarioo.api.exception.ResourceNotFoundException;
import org.scenarioo.business.builds.BuildLink;
import org.scenarioo.dao.aggregates.ScenarioDocuAggregationDAO;
import org.scenarioo.model.docu.aggregates.branches.BuildImportStatus;
import org.scenarioo.model.docu.aggregates.branches.BuildImportSummary;
import org.scenarioo.model.docu.aggregates.branches.BuildStatistics;
import org.scenarioo.model.docu.aggregates.objects.LongObjectNamesResolver;
import org.scenarioo.model.docu.aggregates.scenarios.PageSteps;
import org.scenarioo.model.docu.aggregates.scenarios.ScenarioPageSteps;
import org.scenarioo.model.docu.aggregates.usecases.ScenarioSummary;
import org.scenarioo.model.docu.aggregates.usecases.UseCaseScenarios;
import org.scenarioo.model.docu.aggregates.usecases.UseCaseScenariosList;
import org.scenarioo.model.docu.entities.Scenario;
import org.scenarioo.model.docu.entities.Step;
import org.scenarioo.model.docu.entities.UseCase;
import org.scenarioo.model.docu.entities.generic.ObjectReference;
import org.scenarioo.repository.ConfigurationRepository;
import org.scenarioo.repository.RepositoryLocator;
import org.scenarioo.rest.base.BuildIdentifier;

/**
 * The aggregator reads the input docu files of a build and generates the aggregated docu files with additional
 * precalculated data (like indexes etc.).
 * 
 * Make sure to adjust the value of {@link ScenarioDocuAggregator#CURRENT_FILE_FORMAT_VERSION} when the format of
 * generated data is extended or changed.
 * 
 * TODO #194: Make build import more friendly:<br>
 * Make aggregator more fail safe ... let him continue in case of exceptions or unexpected data (null pointers?) to
 * aggregate at least that part of a documentation build that is okay, such that this part can be accessed and read.
 */
public class ScenarioDocuAggregator {
	
	private static final String SUCCESS_STATE = "success";
	
	private static final String FAILED_STATE = "failed";
	
	/**
	 * Version of the file format in filesystem. The data aggregator checks whether the file format is the same,
	 * otherwise the data has to be recalculated.
	 */
	public static final String CURRENT_FILE_FORMAT_VERSION = "2.0.0";
	
	private final static Logger LOGGER = Logger.getLogger(ScenarioDocuAggregator.class);
	
	private final ConfigurationRepository configurationRepository = RepositoryLocator.INSTANCE
			.getConfigurationRepository();
	
	/**
	 * The build this aggregator is currently aggregating.
	 */
	private final BuildIdentifier buildIdentifier;
	
	private final ScenarioDocuReader reader = new ScenarioDocuReader(
			configurationRepository.getDocumentationDataDirectory());
	
	private final LongObjectNamesResolver longObjectNamesResolver = new LongObjectNamesResolver();
	
	private final ScenarioDocuAggregationDAO dao = new ScenarioDocuAggregationDAO(
			configurationRepository.getDocumentationDataDirectory(), longObjectNamesResolver);
	
	private final BuildStatistics buildStatistics = new BuildStatistics();
	
	private StepsAndPagesAggregator stepsAndPagesAggregator;
	
	private ObjectRepository objectRepository;
	
	public ScenarioDocuAggregator(final BuildIdentifier buildIdentifier) {
		this.buildIdentifier = buildIdentifier;
	}
	
	public boolean isAggregatedDataForBuildAlreadyAvailableAndCurrentVersion() {
		String version = dao.loadVersion(buildIdentifier);
		return !StringUtils.isBlank(version) && version.equals(CURRENT_FILE_FORMAT_VERSION);
	}
	
	public void removeAggregatedDataForBuild() {
		dao.deleteDerivedFiles(buildIdentifier);
		objectRepository = new ObjectRepository(buildIdentifier, dao);
		objectRepository.removeAnyExistingObjectData();
	}
	
	public void calculateAggregatedDataForBuild() {
		stepsAndPagesAggregator = new StepsAndPagesAggregator(buildIdentifier, dao);
		
		objectRepository = new ObjectRepository(buildIdentifier, dao);
		objectRepository.removeAnyExistingObjectData();
		
		LOGGER.info("  calculating aggregated data for build " + buildIdentifier + " ... ");
		UseCaseScenariosList useCaseScenariosList = calculateUseCaseScenariosList();
		for (UseCaseScenarios scenarios : useCaseScenariosList.getUseCaseScenarios()) {
			calulateAggregatedDataForUseCase(scenarios);
			buildStatistics.incrementUseCase();
		}
		
		stepsAndPagesAggregator.completeAggregatedPageVariantDataInStepNavigations();
		
		dao.saveUseCaseScenariosList(buildIdentifier.getBranchName(), buildIdentifier.getBuildName(),
				useCaseScenariosList);
		
		objectRepository.calculateAndSaveObjectLists();
		
		objectRepository.saveCustomObjectTabTrees();
		
		dao.saveLongObjectNamesIndex(buildIdentifier, longObjectNamesResolver);
		
		dao.saveVersion(buildIdentifier, CURRENT_FILE_FORMAT_VERSION);
	}
	
	private UseCaseScenariosList calculateUseCaseScenariosList() {
		
		UseCaseScenariosList result = new UseCaseScenariosList();
		List<UseCaseScenarios> useCaseScenarios = new ArrayList<UseCaseScenarios>();
		List<UseCase> usecases = reader.loadUsecases(buildIdentifier.getBranchName(), buildIdentifier.getBuildName());
		for (UseCase usecase : usecases) {
			UseCaseScenarios useCaseWithScenarios = new UseCaseScenarios();
			List<Scenario> scenarios = reader.loadScenarios(buildIdentifier.getBranchName(),
					buildIdentifier.getBuildName(), usecase.getName());
			
			boolean atLeastOneScenarioFailed = false;
			for (Scenario scenario : scenarios) {
				if (StringUtils.equals(scenario.getStatus(), FAILED_STATE)) {
					atLeastOneScenarioFailed = true;
					break;
				}
			}
			
			if (usecase.getStatus() == null) {
				usecase.setStatus(atLeastOneScenarioFailed ? FAILED_STATE : SUCCESS_STATE);
			}
			useCaseWithScenarios.setScenarios(createScenarioSummaries(scenarios));
			useCaseWithScenarios.setUseCase(usecase);
			useCaseScenarios.add(useCaseWithScenarios);
		}
		result.setUseCaseScenarios(useCaseScenarios);
		return result;
	}
	
	private List<ScenarioSummary> createScenarioSummaries(final List<Scenario> scenarios) {
		List<ScenarioSummary> scenarioSummaries = new ArrayList<ScenarioSummary>(scenarios.size());
		for (Scenario scenario : scenarios) {
			scenarioSummaries.add(createSummary(scenario));
		}
		return scenarioSummaries;
	}
	
	private ScenarioSummary createSummary(final Scenario scenario) {
		ScenarioSummary summary = new ScenarioSummary();
		summary.setScenario(scenario);
		return summary;
	}
	
	private void calulateAggregatedDataForUseCase(final UseCaseScenarios useCaseScenarios) {
		
		LOGGER.info("    calculating aggregated data for use case : " + useCaseScenarios.getUseCase().getName());
		
		List<ObjectReference> referencePath = objectRepository.addReferencedUseCaseObjects(useCaseScenarios
				.getUseCase());
		
		for (ScenarioSummary scenario : useCaseScenarios.getScenarios()) {
			try {
				calculateAggregatedDataForScenario(referencePath, useCaseScenarios.getUseCase(), scenario);
				addScenarioStatistics(scenario.getScenario());
			} catch (ResourceNotFoundException ex) {
				LOGGER.warn("could not load scenario " + scenario.getScenario().getName() + " in use case"
						+ useCaseScenarios.getUseCase().getName());
			}
		}
		dao.saveUseCaseScenarios(buildIdentifier, useCaseScenarios);
		
		objectRepository.updateAndSaveObjectIndexesForCurrentCase();
	}
	
	private void addScenarioStatistics(final Scenario scenario) {
		String status = scenario.getStatus();
		if (SUCCESS_STATE.equals(status)) {
			buildStatistics.incrementSuccessfulScenario();
		} else if (FAILED_STATE.equals(status)) {
			buildStatistics.incrementFailedScenario();
		}
	}
	
	private void calculateAggregatedDataForScenario(List<ObjectReference> referencePath, final UseCase usecase,
			final ScenarioSummary scenarioSummary) {
		Scenario scenario = scenarioSummary.getScenario();
		
		referencePath = objectRepository.addReferencedScenarioObjects(referencePath, scenario);
		
		LOGGER.info("      calculating aggregated data for scenario : " + scenario.getName());
		ScenarioPageSteps scenarioPageSteps = calculateAggregatedDataForSteps(usecase, scenario, referencePath);
		
		scenarioSummary.setNumberOfSteps(scenarioPageSteps.getTotalNumberOfStepsInScenario());
		
		dao.saveScenarioPageSteps(buildIdentifier, scenarioPageSteps);
	}
	
	private ScenarioPageSteps calculateAggregatedDataForSteps(final UseCase usecase, final Scenario scenario,
			final List<ObjectReference> referencePath) {
		
		ScenarioPageSteps scenarioPageSteps = new ScenarioPageSteps();
		scenarioPageSteps.setUseCase(usecase);
		scenarioPageSteps.setScenario(scenario);
		List<Step> steps = reader.loadSteps(buildIdentifier.getBranchName(), buildIdentifier.getBuildName(),
				usecase.getName(), scenario.getName());
		PageNameSanitizer.sanitizePageNames(steps);
		List<PageSteps> pageStepsList = stepsAndPagesAggregator.calculateScenarioPageSteps(usecase, scenario, steps,
				referencePath, objectRepository);
		scenarioPageSteps.setPagesAndSteps(pageStepsList);
		
		return scenarioPageSteps;
	}
	
	public void updateBuildSummary(final BuildImportSummary buildSummary, final BuildLink buildLink) {
		BuildIdentifier buildIdentifier = buildSummary.getIdentifier();
		buildSummary.setBuildDescription(buildLink.getBuild());
		String version = dao.loadVersion(buildIdentifier);
		boolean aggregated = !StringUtils.isBlank(version);
		boolean outdated = aggregated && !version.equals(CURRENT_FILE_FORMAT_VERSION);
		boolean error = buildSummary.getStatus().isFailed();
		if (error) {
			buildSummary.setStatus(BuildImportStatus.FAILED);
		} else if (outdated) {
			buildSummary.setStatus(BuildImportStatus.OUTDATED);
		} else if (aggregated) {
			buildSummary.setStatus(BuildImportStatus.SUCCESS);
		} else {
			buildSummary.setStatus(BuildImportStatus.UNPROCESSED);
		}
	}
	
	public BuildStatistics getBuildStatistics() {
		return this.buildStatistics;
	}
}
