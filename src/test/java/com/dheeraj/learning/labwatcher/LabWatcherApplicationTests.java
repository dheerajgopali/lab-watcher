package com.dheeraj.learning.labwatcher;

import com.dheeraj.learning.labwatcher.dao.PerfStatDAO;
import com.dheeraj.learning.labwatcher.entity.PerfStat;
import com.dheeraj.learning.labwatcher.service.EmailService;
import com.dheeraj.learning.labwatcher.service.SchedulerService;
import com.dheeraj.learning.labwatcher.util.DataUtil;
import com.dheeraj.learning.labwatcher.util.DateUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

@RunWith(SpringRunner.class)
@SpringBootTest
public class LabWatcherApplicationTests {

    @Autowired
    SchedulerService schedulerService;

    @Autowired
    PerfStatDAO perfStatDAO;


    //@Test
    public void contextLoads() {
        //TODO : Remove DTOs and use only Entity objects everywhere.
        schedulerService.analyseAScenarioLatestBuild();


    }

    @Test
    public void testEmail() {
        EmailService.sendEmail(DataUtil.getScenarioDataDTO());
    }

    public void testGetValidBuildLabelsBetweenGivenDates() {
        String scenarioName = "CCCASE";
        String prpcVersion = "8.2.0";
        String startDate = "2018-08-25";
        String endDate = "2018-12-11";

        List<String> validBuildLabels = perfStatDAO.getValidBuildLabelsBetweenGivenDates(scenarioName, prpcVersion, startDate, endDate);

        System.out.println(validBuildLabels);
    }


    public void testGetScenarioData() {
        System.out.println(perfStatDAO.getScenarioData("CCCASE", "PRPC-HEAD-6577"));
    }


    //@Test
    public void testGetLatestBuilds() {
        List<PerfStat> perfStats = perfStatDAO.getBuilds("2019-03-26");
        System.out.println(perfStats.size());
        for (PerfStat perfStat : perfStats) {
            System.out.println("BuildLabel : "+perfStat.getBuildlabel()+", ScenarioName : "+perfStat.getTestname());
        }
    }

    //@Test
    public void testDateUtil() {
        System.out.println(DateUtil.getDates("2019-03-26", 1));
    }
}
