11 - http://localhost:3000/d/KVr-Vmpnz/services-statistic?orgId=1&from=2025-10-24T15:40:05.451Z&to=2025-10-24T15:45:02.425Z&timezone=browser&var-service=m3402-Random&refresh=5s

<img width="1512" height="834" alt="Screenshot 2025-10-24 at 18 45 32" src="https://github.com/user-attachments/assets/f51573fa-6291-4b55-bd03-53e87a343f04" />

<img width="1512" height="590" alt="Screenshot 2025-10-24 at 18 45 36" src="https://github.com/user-attachments/assets/5eb9bd32-ad03-4ef2-8f4a-22e921928889" />

<img width="1511" height="829" alt="Screenshot 2025-10-24 at 18 45 40" src="https://github.com/user-attachments/assets/8906a2e1-ae48-4e49-8595-ec708dbccb6b" />

10 - http://localhost:3000/d/KVr-Vmpnz/services-statistic?orgId=1&from=2025-10-24T15:46:58.002Z&to=2025-10-24T15:52:40.365Z&timezone=browser&var-service=$__all&refresh=5s

<img width="1511" height="724" alt="Screenshot 2025-10-24 at 18 53 39" src="https://github.com/user-attachments/assets/e922d4e6-615c-47eb-95df-5dad91738161" />
<img width="1512" height="536" alt="Screenshot 2025-10-24 at 18 53 43" src="https://github.com/user-attachments/assets/07adc7da-c8c4-4932-ab1d-a88e2596842b" />
<img width="1512" height="779" alt="Screenshot 2025-10-24 at 18 53 47" src="https://github.com/user-attachments/assets/453c9c23-ac06-45ac-b9d5-b3387b898c0f" />



# Template for the HighLoad course
This project is based on [Tiny Event Sourcing library](https://github.com/andrsuh/tiny-event-sourcing)

### Run PostgreSql
This example uses Postgres as an implementation of the Event store. You can see it in `pom.xml`:

```
<dependency>
    <groupId>ru.quipy</groupId>
    <artifactId>tiny-postgres-event-store-spring-boot-starter</artifactId>
    <version>${tiny.es.version}</version>
</dependency>
```

Thus, you have to run Postgres in order to test this example. Postgres service is included in  `docker-compose` file that we have in the root of the project.

# More comprehensive information about the course, project, how to run tests is here:

https://andrsuh.notion.site/2595d535059281d8a815c2cb3875c376?source=copy_link

https://andrsuh.notion.site/2625d5350592801aaf88c7c95302d10c?source=copy_link

### Run the infrastructure
Set of the services you need to start developing and testing process is following:
- Bombardier - service that is in charge of emulation the store's clients activity (creates the incoming load). Also serves as a third-party payment system.
- Postgres DBMS
- Prometheus + Grafana - metrics collection and visualization services

You can run all beforementioned services by the following command:
```
docker compose -f docker-compose.yml up
```

### Run the application
To make the application run you can start the main class `OnlineShopApplication`. It is not being launched as a docker contained to simplify and speed up the devevopment process as it is easier for you to refactor the application and re-run it immediately in the IDE.


### If you want to pull changes from the main repository into your fork

The command ```git remote -v``` should include the following lines:

```
upstream        https://github.com/andrsuh/high-load-course.git (fetch)
upstream        https://github.com/andrsuh/high-load-course.git (push)
```

If not, add the upstream remote:
```git remote add upstream https://github.com/andrsuh/high-load-course.git```

To pull changes from the main repository, run the following commands:

```
git fetch upstream
# switch to the main branch of your fork. Make sure the branch has no uncommitted changes to avoid conflicts
git checkout main 
# merge changes from the main repository into your main branch
git merge upstream/main 
```
