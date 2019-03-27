package com.dheeraj.learning.labwatcher.service;

import com.dheeraj.learning.labwatcher.dao.PerfStatDAO;
import com.dheeraj.learning.labwatcher.dto.ParamDataDTO;
import com.dheeraj.learning.labwatcher.dto.PerfStatDTO;
import com.dheeraj.learning.labwatcher.dto.ScenarioDataDTO;
import com.dheeraj.learning.labwatcher.entity.ParamData;
import com.dheeraj.learning.labwatcher.entity.PerfStat;
import com.dheeraj.learning.labwatcher.entity.ScenarioData;
import com.dheeraj.learning.labwatcher.repository.ScenarioDataRepository;
import com.dheeraj.learning.labwatcher.util.DataUtil;
import com.dheeraj.learning.labwatcher.util.DegradationIdentificationUtil;
import com.dheeraj.learning.labwatcher.util.Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.tags.Param;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is the main service method to do business logic on performance stats.
 *
 * Methods plan
 * 1.getValidBuildLabelsBetweenGivenDates
 * 2.callAScenario
 */
@Service
public class PerfStatService {

    Logger logger = LoggerFactory.getLogger(PerfStatService.class);

    public static Integer MAX_DATA_SIZE=50;
    public static Integer DECENT_DATA_SIZE=20;
    public static Integer MIN_DATA_SIZE=5;
    public static Double MAX_DATA_ACCURACY=70.0;
    public static Double DECENT_DATA_ACCURACY=60.0;
    public static Double MIN_DATA_ACCURACY=60.0;
    public static Double MIN_ACCURACY_FOR_EMAIL=80.0;

    @Autowired
    private PerfStatDAO perfStatDAO;

    @Autowired
    private ScenarioDataRepository scenarioDataRepository;

    /**
     * This method analyzes last n (maxResults #50 for now) perfstats(previous to the #testBuild} and calculates mean and
     * standard deviation for the given parameters.
     * Then it calculates if the #testBuild metrics are degraded/improved based on how far they are from standard deviation.
     *
     * @param scenarioName Scenario to be tested.
     * @param paramList    Parameters to be tested.
     * @param prpcVersion  Prpcversion of the testing builds.
     * @param testBuild    The build for which degradation analysis to be done.
     * @return This an object which contains all the degradation details.
     */
    public ScenarioDataDTO callAScenario(String scenarioName, List<String> paramList, String prpcVersion, String testBuild, boolean isHead) {
        //TODO : map/create exact parameters from entity to dto.
        ScenarioDataDTO scenarioDataDTO = new ScenarioDataDTO();
        scenarioDataDTO.setTestname(scenarioName);
        scenarioDataDTO.setLatestbuild(testBuild);


        Map<String, ParamDataDTO> currentBuildParamMap = analyseData(scenarioName, paramList, prpcVersion, testBuild, isHead);
        scenarioDataDTO.setMap(currentBuildParamMap);

        //Saving only if atleast one parameter is degraded/improved;
        //Ensure that this works even if we rerun the analysis for the same build.
        boolean isVaried = DegradationIdentificationUtil.isAnyParamVaried(currentBuildParamMap);
        if(isVaried) {
            ScenarioData scenarioData = perfStatDAO.getScenarioData(scenarioName, testBuild);
            if(scenarioData!= null)
                Mapper.map(scenarioDataDTO, scenarioData, paramList);
            else
                scenarioData = Mapper.convert(scenarioDataDTO);

            scenarioDataRepository.save(scenarioData);
        }

        return scenarioDataDTO;
    }

    public boolean hasMinimumAccuracy(Map<String, ParamDataDTO> paramDataDTOMap, Double minAccuracy) {
        for (String param : paramDataDTOMap.keySet()) {
            if(paramDataDTOMap.get(param).getAccuracy() >= minAccuracy)
                return true;
        }
        return false;
    }

    /**
     * This method contains the important logic to identify degradation.
     * This method can be improved to add more configuraiton stuff.
     * @param scenarioName
     * @param paramList
     * @param prpcVersion
     * @param currentBuildLabel
     * @return
     */
    public Map<String, ParamDataDTO> analyseData(String scenarioName, List<String> paramList, String prpcVersion, String currentBuildLabel, boolean isHead) {

        Map<String, ParamDataDTO> variedBuildRankMap = getLastVariedBuildDetails(scenarioName, paramList, currentBuildLabel, prpcVersion, isHead);
        Map<String, ParamDataDTO> currentBuildParamMap = createMapOfGivenParams(paramList, scenarioName, currentBuildLabel);

        //For loop over params
        for (String param : currentBuildParamMap.keySet()) {
            ParamDataDTO variedBuildParamDataDTO = variedBuildRankMap.get(param);
            //The second condition occurs below only when the first condition is false
            if( variedBuildParamDataDTO  == null) {
                analyseWhenResultsAreStableForNBuilds(scenarioName, prpcVersion, currentBuildLabel, currentBuildParamMap, param, MAX_DATA_SIZE, MAX_DATA_ACCURACY);
            } else if (variedBuildParamDataDTO.getVariedBuildRank() > DECENT_DATA_SIZE) {
                analyseWhenResultsAreStableForNBuilds(scenarioName, prpcVersion, currentBuildLabel, currentBuildParamMap, param, variedBuildParamDataDTO.getVariedBuildRank(), DECENT_DATA_ACCURACY);
            } else if (variedBuildParamDataDTO.getVariedBuildRank() >= MIN_DATA_SIZE && variedBuildParamDataDTO.getVariedBuildRank() <= DECENT_DATA_SIZE){
                analyseWhenResultsAreStableForNBuilds(scenarioName, prpcVersion, currentBuildLabel, currentBuildParamMap, param, variedBuildParamDataDTO.getVariedBuildRank(), MIN_DATA_ACCURACY);
            } else {
                int rank = variedBuildParamDataDTO.getVariedBuildRank();
                Double accuracy = 0.0;
                if (variedBuildParamDataDTO.isDegraded()) {
                    accuracy = analyseWhenRecentBuildsHaveVariation(false, scenarioName, prpcVersion, currentBuildLabel, variedBuildRankMap.get(param).getBuildLabel(), param, rank, true);
                } else if (variedBuildParamDataDTO.isImproved()) {
                    accuracy = analyseWhenRecentBuildsHaveVariation(false, scenarioName, prpcVersion, currentBuildLabel, variedBuildRankMap.get(param).getBuildLabel(), param, rank, false);
                } else {
                    logger.debug("Though this param is neither improved nor degraded somehow this data got into database incorrectly.");
                }

                decideAndSendEmail(variedBuildParamDataDTO, accuracy);
            }
        }
        return currentBuildParamMap;
    }

    public void decideAndSendEmail(ParamDataDTO variedBuildParamDTO, Double accuracy) {
        boolean sendEmail = false;
        Integer rank = variedBuildParamDTO.getVariedBuildRank();
        if(rank >= 3) {
            if(rank == 3 && accuracy >= 90) {
                sendEmail = true;
            } else if (rank == 4 && accuracy >= 80) {
                sendEmail = true;
            }
        }
        if(sendEmail) {
            ScenarioData scenarioData = perfStatDAO.getScenarioData(variedBuildParamDTO.getScenarioName(), variedBuildParamDTO.getBuildLabel());
            ScenarioDataDTO scenarioDataDTO = Mapper.convert(scenarioData);
            EmailService.sendEmail(scenarioDataDTO);
        }
    }

    private Double analyseWhenRecentBuildsHaveVariation(boolean isAverageRecentBuilds, String scenarioName, String prpcVersion, String currentBuildLabel, String degradedBuild, String param, Integer rank, boolean isForDegradationCheck) {
        Double currentParamValue;
        if(isAverageRecentBuilds) {  //This logic takes the average of the last n (<5) builds and compare with the last degraded value.
            List<PerfStat> perfStats = perfStatDAO.getPerfStatsBetweenBuilds(scenarioName, prpcVersion, degradedBuild, currentBuildLabel, rank);

            List<PerfStatDTO> perfStatDTOs = Mapper.copyResultsToDTO(perfStats);
            Double consolidatedMean = DegradationIdentificationUtil.getMean(perfStatDTOs, param);
            currentParamValue = consolidatedMean;
        } else { //This logic just compares the current value with last degraded value.
            PerfStat currentBuildPerfStat = perfStatDAO.getPerfStatForAGivenBuild(scenarioName, currentBuildLabel);
            PerfStatDTO perfStatDTO = new PerfStatDTO();
            BeanUtils.copyProperties(currentBuildPerfStat, perfStatDTO);

            currentParamValue = perfStatDTO.getDouble(param);
        }

        ParamData paramData = perfStatDAO.getParamDataForAGivenBuild(scenarioName, param, degradedBuild);
        ParamDataDTO paramDataDTO = Mapper.convert(paramData);

        double variationPercentage = (paramDataDTO.getMean() == 0 ? 0 : (((currentParamValue - paramDataDTO.getMean()) / paramDataDTO.getMean()) * 100));
        boolean isDegraded = DegradationIdentificationUtil.isDegraded(currentParamValue, paramDataDTO.getMean(), paramDataDTO.getStandardDeviation(), 2, true, variationPercentage);
        boolean isImproved = DegradationIdentificationUtil.isImproved(currentParamValue, paramDataDTO.getMean(), paramDataDTO.getStandardDeviation(), 2, true, variationPercentage);
        logger.debug("ScenarioName : "+scenarioName+", currentBuildLabel : "+currentBuildLabel+" Variation status : isDegraded : "+isDegraded+", isImproved : "+isImproved);

        Double accuracy = 0.0;
        if ((isForDegradationCheck && isDegraded) || (!isForDegradationCheck && isImproved)) {
            paramDataDTO.setAccuracy(paramDataDTO.getAccuracy() + 10.0);
            accuracy = paramData.getAccuracy() + 10.0;
            logger.debug("Accuracy increased for scenario : "+paramDataDTO.getScenarioName()+", build : "+paramDataDTO.getBuildLabel()+", param : "+paramDataDTO.getParamName());
        } else {
            paramDataDTO.setAccuracy(paramDataDTO.getAccuracy() - 10.0);
            accuracy = paramData.getAccuracy() - 10.0;
            logger.debug("Accuracy decreased for scenario : "+paramDataDTO.getScenarioName()+", build : "+paramDataDTO.getBuildLabel()+", param : "+paramDataDTO.getParamName());
        }

        //Send Email
        //Send the last degraded content as email //Also put accuracy in the email.

        //Check if the last degradation/baseline is an outlier. If it is then remove/update it from database that it is not degraded.
        boolean isLastVariationInvalid = isLastVariationInvalid(rank, accuracy);
        if(!isLastVariationInvalid) {
            perfStatDAO.findAndRemoveVariation(paramData);
            //Rerun later builds after this degradation.
            rerunLaterBuildsAfterDegradation(scenarioName, prpcVersion, rank, currentBuildLabel, paramData);
        } else {
            //Can change this save logic later
            perfStatDAO.findAndUpdate(paramData, accuracy);
        }

        return accuracy;
    }

    /**
     * TODO Make use of rank attribute from the caller to cross check the data.
     * @param scenarioName
     * @param prpcVersion
     * @param rank
     * @param endBuildLabel
     */
    public void rerunLaterBuildsAfterDegradation(String scenarioName, String prpcVersion, Integer rank, String endBuildLabel, ParamData paramData) {
        List<PerfStat> perfStats = perfStatDAO.getPerfStatsForLastNBuilds(scenarioName, prpcVersion, endBuildLabel, rank-1, true);
        List<String> paramList = new ArrayList<>();
        paramList.add(paramData.getParamName());
        for (PerfStat perfstat : perfStats) {
            callAScenario(scenarioName, paramList, prpcVersion, perfstat.getBuildlabel(), true);
        }
    }

    /**
     * When there is a degradation/baseline in the last 5 builds and 2 in the last 4 builds doesnt follow the same trend, then
     * it is considered that the last degradation is invalid and it is removed from the database.
     *
     * The below logic is simplified in the method.
     * if(rank == 1 && accuracy >= 50)
     *             return true;
     *         else if(rank == 2 && accuracy >= 60)
     *             return true;
     *         else if (rank == 3 && accuracy >= 70)
     *             return true;
     *         else if (rank == 4 && accuracy >= 80)
     *             return true;
     *         else
     *             return false;
     *
     * @param rank
     * @param accuracy
     * @return
     */
    public boolean isLastVariationInvalid(Integer rank, Double accuracy) {
        if(accuracy >= (40+rank*10))
            return true;
        else
            return false;
    }

    /**
     * When there are no major deviations in the last #DECENT_DATA_SIZE number of builds, this method is executed to
     * identify if the current build as degraded/improved.
     *
     * @param scenarioName
     * @param prpcVersion
     * @param currentBuildLabel
     * @param currentBuildParamMap
     * @param param
     */
    private void analyseWhenResultsAreStableForNBuilds(String scenarioName, String prpcVersion, String currentBuildLabel,
                                                       Map<String, ParamDataDTO> currentBuildParamMap, String param, Integer rank,
                                                       Double accuracy) {
        //TODO: Yet to write logic to identify if the build is HEAD or not.
        List<PerfStat> perfStats = perfStatDAO.getPerfStatsForLastNBuilds(scenarioName, prpcVersion, currentBuildLabel, rank, true);

        List<PerfStatDTO> perfStatDTOs = Mapper.copyResultsToDTO(perfStats);

        //Calc mean and std
        DegradationIdentificationUtil.calcStandardDeviation(perfStatDTOs, param, currentBuildParamMap.get(param));

        //Get latset bulid values
        PerfStat currentBuildPerfStat = perfStatDAO.getPerfStatForAGivenBuild(scenarioName, currentBuildLabel);
        PerfStatDTO currentBuildPerfStatDTO = new PerfStatDTO();
        BeanUtils.copyProperties(currentBuildPerfStat, currentBuildPerfStatDTO);

        //Compare
        boolean isVaried = DegradationIdentificationUtil.isVaried(currentBuildParamMap.get(param), param, currentBuildPerfStatDTO.getDouble(param));
        if(isVaried) {
            currentBuildParamMap.get(param).setAccuracy(accuracy);
            DataUtil.printVariationMessage(currentBuildParamMap.get(param));
        }
    }

    private static Map<String, ParamDataDTO> createMapOfGivenParams(List<String> paramList, String scenarioName, String buildLabel) {
        Map<String, ParamDataDTO> currentBuildParamMap = new HashMap<>();
        for (String param : paramList) {
            currentBuildParamMap.put(param, new ParamDataDTO(param, scenarioName, buildLabel));
        }
        return currentBuildParamMap;
    }

    /**
     * This method retrieves the builds(#with ocmplete parameter data) which were degraded/improved earlier for the given scenario.
     * @param scenarioName
     * @param paramList
     * @param currentBuildLabel
     * @return
     */
    private Map<String, ParamDataDTO> getLastVariedBuildDetails(String scenarioName, List<String> paramList, String currentBuildLabel, String prpcVersion, boolean isHead) {
        Map<String, ParamDataDTO> variedBuildRankMap = new HashMap<>();
        for (String param : paramList) {
            ParamData paramData = perfStatDAO.getVariedBuildRankDetails(scenarioName, param, currentBuildLabel, prpcVersion, isHead);
            ParamDataDTO paramDataDTO = null;
            if(paramData != null)
                paramDataDTO = Mapper.convert(paramData);

            variedBuildRankMap.put(param, paramDataDTO);
        }
        return variedBuildRankMap;
    }

    public List<PerfStatDTO> getPerfStatsBetweenBuilds(String scenarioName, String prpcVersion, String startBuildLabel, String endBuildLabel, int maxResults) {
        List<PerfStat> list = perfStatDAO.getPerfStatsBetweenBuilds(scenarioName, prpcVersion, startBuildLabel, endBuildLabel, maxResults);
        List<PerfStatDTO> dtoList = Mapper.copyResultsToDTO(list);
        return dtoList;
    }

    public PerfStatDTO getPerfStatForAGivenBuild(String scenarioName, String buildLabel) {
        PerfStat perfStat = perfStatDAO.getPerfStatForAGivenBuild(scenarioName, buildLabel);
        PerfStatDTO perfStatDTO = new PerfStatDTO();
        BeanUtils.copyProperties(perfStat, perfStatDTO);
        return  perfStatDTO;
    }


    public List<PerfStatDTO> getPerfStatsForLastNBuilds(String scenarioName, String prpcVersion,  String endBuildLabel, int maxResults, boolean isHead) {
        List<PerfStat> list = perfStatDAO.getPerfStatsForLastNBuilds(scenarioName, prpcVersion, endBuildLabel, maxResults, isHead);

        List<PerfStatDTO> dtoList = Mapper.copyResultsToDTO(list);
        return dtoList;
    }

    public List<String> getValidBuildLabelsBetweenGivenDates(String scenarioName, String prpcVersion, String startDate, String endDate) {
        List<String> list = perfStatDAO.getValidBuildLabelsBetweenGivenDates(scenarioName, prpcVersion, startDate, endDate);
        return list;
    }
}
